package com.betteranalytics.crm.service;

import com.betteranalytics.crm.config.IntegrationAsyncConfig.IntegrationExecutor;
import com.betteranalytics.crm.entity.Campaign;
import com.betteranalytics.crm.entity.CampaignContact;
import com.betteranalytics.crm.entity.WhatsAppAccount;
import com.betteranalytics.crm.entity.WhatsAppMessage;
import com.betteranalytics.crm.repository.CampaignContactRepository;
import com.betteranalytics.crm.repository.CampaignRepository;
import com.betteranalytics.crm.repository.WhatsAppAccountRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Background sender for WhatsApp campaigns.
 *
 * Guarantees:
 *   - a recipient row is the send record: unique (campaign_id, normalized_phone),
 *     status pending → sending → sent/failed (→ delivered → read via webhooks) or
 *     skipped_opted_out; the same number is never messaged twice by a campaign;
 *   - recipients are claimed in small batches (50) — on PostgreSQL with
 *     SELECT … FOR UPDATE SKIP LOCKED, elsewhere (H2) with one atomic conditional
 *     UPDATE per row — so concurrent workers never claim the same row;
 *   - no Graph API call runs inside a transaction: claim (own tx) → HTTP send
 *     (no tx, timeouts) → result (own tx, compare-and-set on 'sending');
 *   - claims older than 15 minutes (crashed worker) are reset to pending;
 *   - pause / cancel is honoured between recipients; unprocessed claims are released.
 */
@Service
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);
    static final int CLAIM_BATCH = 50;
    static final Duration STALE_CLAIM = Duration.ofMinutes(15);

    private final CampaignRepository campaignRepo;
    private final CampaignContactRepository contactRepo;
    private final WhatsAppAccountRepository accountRepo;
    private final WhatsAppService whatsAppService;
    private final WhatsAppConsentService consentService;
    private final IntegrationExecutor executor;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;

    /** Campaign ids with a dispatch worker running in this JVM (row claims guard across JVMs). */
    private final Set<Long> dispatching = Collections.synchronizedSet(new HashSet<>());
    private volatile Boolean postgres;

    public CampaignDispatcher(CampaignRepository campaignRepo,
                              CampaignContactRepository contactRepo,
                              WhatsAppAccountRepository accountRepo,
                              WhatsAppService whatsAppService,
                              WhatsAppConsentService consentService,
                              IntegrationExecutor executor,
                              JdbcTemplate jdbc,
                              DataSource dataSource,
                              PlatformTransactionManager txManager) {
        this.campaignRepo = campaignRepo;
        this.contactRepo = contactRepo;
        this.accountRepo = accountRepo;
        this.whatsAppService = whatsAppService;
        this.consentService = consentService;
        this.executor = executor;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = new TransactionTemplate(txManager);
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    /** Queue a background dispatch — after the caller's transaction commits, if any. */
    public void dispatchAsync(Long campaignId) {
        Runnable submit = () -> {
            try {
                executor.submit("campaign-dispatch-" + campaignId, () -> dispatch(campaignId));
            } catch (TaskRejectedException e) {
                log.warn("Campaign {} dispatch deferred — executor queue full; scheduler will retry", campaignId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else {
            submit.run();
        }
    }

    /** Sends to every pending recipient while the campaign stays 'sending'. */
    public void dispatch(Long campaignId) {
        if (!dispatching.add(campaignId)) return;
        try {
            Campaign c = campaignRepo.findById(campaignId).orElse(null);
            if (c == null || !"sending".equals(c.getStatus()) || !"whatsapp".equals(c.getType())) return;

            WhatsAppAccount account = resolveAccount(c);
            if (account == null || !account.isActive()) {
                log.warn("Campaign {} has no active WhatsApp account; recipients left pending", campaignId);
                return;
            }
            List<Map<String, Object>> components;
            String language;
            String templateName;
            try {
                components = whatsAppService.parseTemplateComponents(c.getTemplateParams());
                language = WhatsAppService.validateLanguage(c.getTemplateLanguage());
                templateName = WhatsAppService.validateTemplateName(c.getTemplateName());
            } catch (IllegalArgumentException e) {
                log.warn("Campaign {} has invalid template settings: {}", campaignId, e.getMessage());
                return;
            }

            while (true) {
                if (!"sending".equals(campaignRepo.findStatusById(campaignId).orElse(null))) break;
                List<Long> claimed = claimBatch(campaignId, CLAIM_BATCH);
                if (claimed.isEmpty()) break;
                List<CampaignContact> rows = contactRepo.findAllById(claimed);
                rows.sort(Comparator.comparing(CampaignContact::getId));
                Set<String> optedOut = consentService.optedOut(c.getOrganizationId(),
                        rows.stream().map(CampaignContact::getNormalizedPhone).toList());

                List<Long> unprocessed = new ArrayList<>(claimed);
                boolean stopped = false;
                int sinceCheck = 0;
                for (CampaignContact cc : rows) {
                    if (++sinceCheck % 10 == 0
                            && !"sending".equals(campaignRepo.findStatusById(campaignId).orElse(null))) {
                        stopped = true;
                        break;
                    }
                    sendOne(c, account, templateName, language, components, cc, optedOut);
                    unprocessed.remove(cc.getId());
                }
                if (stopped) {
                    if (!unprocessed.isEmpty()) contactRepo.releaseClaims(unprocessed);
                    break;
                }
                refreshCounts(campaignId);
            }
        } finally {
            dispatching.remove(campaignId);
            refreshCounts(campaignId);
            completeIfDone(campaignId);
        }
    }

    private void sendOne(Campaign c, WhatsAppAccount account, String templateName, String language,
                         List<Map<String, Object>> components, CampaignContact cc, Set<String> optedOut) {
        String status;
        String waId = null;
        String reason = null;
        try {
            String normalized = cc.getNormalizedPhone() != null ? cc.getNormalizedPhone()
                    : PhoneNumbers.tryNormalize(cc.getPhoneNumber());
            if (normalized == null) {
                status = "failed";
                reason = "Invalid phone number";
            } else if (optedOut.contains(normalized) || consentService.isOptedOut(c.getOrganizationId(), normalized)) {
                status = "skipped_opted_out";
                reason = "Recipient opted out of WhatsApp messages";
            } else {
                WhatsAppMessage msg = whatsAppService.sendCampaignTemplate(account, normalized, templateName,
                        language, components, c.getId());
                boolean failed = "failed".equals(msg.getStatus());
                status = failed ? "failed" : "sent";
                waId = msg.getWaMessageId();
                if (failed) reason = truncate(msg.getErrorMessage(), 300);
            }
        } catch (Exception e) {
            log.warn("Campaign {} recipient {} failed: {}", c.getId(), cc.getId(), e.toString());
            status = "failed";
            reason = "Send failed";
        }
        contactRepo.markResult(cc.getId(), status, waId, reason, Instant.now());
    }

    /**
     * Claims up to {@code limit} pending recipients in one short transaction and
     * returns their ids. PostgreSQL: row locks with SKIP LOCKED so parallel workers
     * get disjoint batches. Other databases: conditional UPDATE per candidate row.
     */
    List<Long> claimBatch(Long campaignId, int limit) {
        Instant now = Instant.now();
        if (isPostgres()) {
            List<Long> ids = tx.execute(s -> {
                List<Long> locked = jdbc.queryForList(
                        "SELECT id FROM campaign_contacts WHERE campaign_id = ? AND status = 'pending' " +
                        "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED", Long.class, campaignId, limit);
                if (locked.isEmpty()) return locked;
                String in = String.join(",", Collections.nCopies(locked.size(), "?"));
                List<Object> args = new ArrayList<>();
                args.add(Timestamp.from(now));
                args.add(Timestamp.from(now));
                args.addAll(locked);
                jdbc.update("UPDATE campaign_contacts SET status = 'sending', claimed_at = ?, updated_at = ? " +
                            "WHERE id IN (" + in + ")", args.toArray());
                return locked;
            });
            return ids == null ? List.of() : ids;
        }
        List<Long> claimed = new ArrayList<>();
        for (Long id : contactRepo.findPendingIds(campaignId, PageRequest.of(0, limit))) {
            if (contactRepo.claimForSending(id, now) == 1) claimed.add(id);
        }
        return claimed;
    }

    boolean isPostgres() {
        Boolean p = postgres;
        if (p == null) {
            try (Connection con = dataSource.getConnection()) {
                p = con.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
            } catch (Exception e) {
                p = false;
            }
            postgres = p;
        }
        return p;
    }

    private WhatsAppAccount resolveAccount(Campaign c) {
        if (c.getWaAccountId() != null) {
            return accountRepo.findByIdAndOrganizationId(c.getWaAccountId(), c.getOrganizationId()).orElse(null);
        }
        return accountRepo.findFirstByOrganizationIdAndActiveTrue(c.getOrganizationId()).orElse(null);
    }

    // ── Counters / progress ───────────────────────────────────────────────────

    /** Recipient counts by status (pending, sending, sent, delivered, read, failed, skipped_opted_out, …). */
    public Map<String, Long> statusCounts(Long campaignId) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String s : List.of("pending", "sending", "sent", "delivered", "read", "failed", "skipped_opted_out", "skipped")) {
            m.put(s, 0L);
        }
        for (Object[] row : contactRepo.countByStatus(campaignId)) {
            m.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return m;
    }

    /** Recomputes campaign counters (cumulative: read ⊂ delivered ⊂ sent) from one grouped query. */
    public void refreshCounts(Long campaignId) {
        if (campaignId == null) return;
        Map<String, Long> m = statusCounts(campaignId);
        int read = m.get("read").intValue();
        int delivered = m.get("delivered").intValue() + read;
        int sent = m.get("sent").intValue() + delivered;
        int failed = m.get("failed").intValue();
        campaignRepo.updateCounts(campaignId, sent, delivered, read, failed, Instant.now());
    }

    private void completeIfDone(Long campaignId) {
        long open = contactRepo.countByCampaignIdAndStatusIn(campaignId, List.of("pending", "sending"));
        if (open == 0) campaignRepo.markCompleted(campaignId, Instant.now());
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    /**
     * Every minute (one instance cluster-wide via ShedLock): start due scheduled
     * campaigns, reset abandoned claims, and re-dispatch sending campaigns that
     * still have pending recipients (after a restart or a rejected submission).
     */
    @Scheduled(fixedDelayString = "${app.campaigns.scheduler-delay-ms:60000}",
               initialDelayString = "${app.campaigns.scheduler-initial-delay-ms:30000}")
    @SchedulerLock(name = "campaigns.runDue", lockAtMostFor = "PT10M")
    public void runDue() {
        try {
            Instant now = Instant.now();
            int reset = contactRepo.resetStaleClaims(now.minus(STALE_CLAIM), now);
            if (reset > 0) log.warn("Reset {} stale campaign send claims back to pending", reset);

            for (Campaign c : campaignRepo.findByStatusAndScheduledAtLessThanEqual("scheduled", now)) {
                if (campaignRepo.markScheduledSending(c.getId(), now) == 1) {
                    log.info("Scheduled campaign {} started", c.getId());
                    if ("whatsapp".equals(c.getType())) dispatchAsync(c.getId());
                }
            }
            for (Campaign c : campaignRepo.findByStatusAndType("sending", "whatsapp")) {
                if (dispatching.contains(c.getId())) continue;
                if (contactRepo.countByCampaignIdAndStatus(c.getId(), "pending") > 0) dispatchAsync(c.getId());
                else completeIfDone(c.getId());
            }
        } catch (Exception e) {
            log.error("Campaign scheduler run failed: {}", e.toString(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
