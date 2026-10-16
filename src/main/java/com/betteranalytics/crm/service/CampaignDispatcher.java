package com.betteranalytics.crm.service;

import com.betteranalytics.crm.config.IntegrationAsyncConfig.IntegrationExecutor;
import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.sequences.SequenceTemplates;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background sender for WhatsApp, email and SMS campaigns.
 *
 * Guarantees (all channels):
 *   - a recipient row is the send record: unique (campaign_id, normalized_phone)
 *     and (campaign_id, email_normalized), status pending → sending → sent/failed
 *     (→ delivered → read via WhatsApp webhooks), skipped_opted_out or skipped
 *     (no address for the channel); nobody is messaged twice by one campaign;
 *   - recipients are claimed in small batches (50) — on PostgreSQL with
 *     SELECT … FOR UPDATE SKIP LOCKED, elsewhere (H2) with one atomic conditional
 *     UPDATE per row — so concurrent workers never claim the same row;
 *   - no provider call (Graph API, SMTP, Azure SMS) runs inside a transaction:
 *     claim (own tx) → send (no tx, timeouts) → result (own tx, CAS on 'sending');
 *   - claims older than 15 minutes (crashed worker) are reset to pending;
 *   - pause / cancel is honoured between recipients; unprocessed claims are released;
 *   - a failure where provably nothing was sent (mailbox login / connect failure,
 *     SMS gateway auth / 429 / 5xx) releases the batch for a later retry; after
 *     {@value #MAX_NOT_SENT_RUNS} such runs in a row the campaign is paused and its
 *     creator notified. Other failures mark the recipient failed (at most once);
 *   - email and SMS are throttled per mailbox / sender (app.campaigns.email-per-minute,
 *     app.campaigns.sms-per-minute); opt-outs (email_consent / WhatsApp+SMS consent)
 *     are honoured.
 */
@Service
public class CampaignDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatcher.class);
    public static final Set<String> CHANNELS = Set.of("whatsapp", "email", "sms");
    static final int CLAIM_BATCH = 50;
    static final Duration STALE_CLAIM = Duration.ofMinutes(15);
    static final int MAX_NOT_SENT_RUNS = 5;

    private final CampaignRepository campaignRepo;
    private final CampaignContactRepository contactRepo;
    private final WhatsAppAccountRepository accountRepo;
    private final WhatsAppService whatsAppService;
    private final WhatsAppConsentService consentService;
    private final EmailAccountRepository emailAccountRepo;
    private final EmailIntegrationService emailService;
    private final EmailConsentService emailConsentService;
    private final SmsSender smsSender;
    private final SendThrottle throttle;
    private final LeadRepository leadRepo;
    private final ContactRepository crmContactRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final IntegrationExecutor executor;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private volatile int emailPerMinute;
    private volatile int smsPerMinute;

    /** Campaign ids with a dispatch worker running in this JVM (row claims guard across JVMs). */
    private final Set<Long> dispatching = Collections.synchronizedSet(new HashSet<>());
    /** Consecutive dispatch runs that ended on a "nothing was sent" failure, per campaign. */
    private final Map<Long, Integer> notSentRuns = new ConcurrentHashMap<>();
    private volatile Boolean postgres;

    public CampaignDispatcher(CampaignRepository campaignRepo,
                              CampaignContactRepository contactRepo,
                              WhatsAppAccountRepository accountRepo,
                              WhatsAppService whatsAppService,
                              WhatsAppConsentService consentService,
                              EmailAccountRepository emailAccountRepo,
                              EmailIntegrationService emailService,
                              EmailConsentService emailConsentService,
                              SmsSender smsSender,
                              SendThrottle throttle,
                              LeadRepository leadRepo,
                              ContactRepository crmContactRepo,
                              UserRepository userRepo,
                              NotificationService notificationService,
                              IntegrationExecutor executor,
                              JdbcTemplate jdbc,
                              DataSource dataSource,
                              PlatformTransactionManager txManager,
                              @Value("${app.campaigns.email-per-minute:60}") int emailPerMinute,
                              @Value("${app.campaigns.sms-per-minute:60}") int smsPerMinute) {
        this.campaignRepo = campaignRepo;
        this.contactRepo = contactRepo;
        this.accountRepo = accountRepo;
        this.whatsAppService = whatsAppService;
        this.consentService = consentService;
        this.emailAccountRepo = emailAccountRepo;
        this.emailService = emailService;
        this.emailConsentService = emailConsentService;
        this.smsSender = smsSender;
        this.throttle = throttle;
        this.leadRepo = leadRepo;
        this.crmContactRepo = crmContactRepo;
        this.userRepo = userRepo;
        this.notificationService = notificationService;
        this.executor = executor;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = new TransactionTemplate(txManager);
        this.emailPerMinute = emailPerMinute;
        this.smsPerMinute = smsPerMinute;
    }

    /** Current per-mailbox email rate (sends / minute; 0 = unthrottled). */
    public int emailPerMinute() { return emailPerMinute; }

    /** Overrides the rates at runtime (tests, ops). */
    public void setRates(int emailPerMinute, int smsPerMinute) {
        this.emailPerMinute = emailPerMinute;
        this.smsPerMinute = smsPerMinute;
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

    /** One recipient's outcome. {@code retry}: nothing was sent — release the claim and stop this run. */
    record SendResult(String status, String messageId, String reason, boolean retry) {
        static SendResult of(String status, String messageId, String reason) { return new SendResult(status, messageId, reason, false); }
        static SendResult retry(String reason) { return new SendResult(null, null, reason, true); }
    }

    /** A campaign channel prepared for one dispatch run. */
    private interface Channel {
        /** Opted-out keys among the batch (one query). */
        Set<String> optedOut(List<CampaignContact> rows);
        /** Called once per claimed batch before sending (e.g. to load linked records). */
        default void prepareBatch(List<CampaignContact> rows) {}
        SendResult send(CampaignContact cc, Set<String> optedOut) throws InterruptedException;
    }

    /** Sends to every pending recipient while the campaign stays 'sending'. */
    public void dispatch(Long campaignId) {
        if (!dispatching.add(campaignId)) return;
        try {
            Campaign c = campaignRepo.findById(campaignId).orElse(null);
            if (c == null || !"sending".equals(c.getStatus()) || !CHANNELS.contains(c.getType())) return;

            Channel channel = switch (c.getType()) {
                case "whatsapp" -> whatsAppChannel(c);
                case "email" -> emailChannel(c);
                case "sms" -> smsChannel(c);
                default -> null;
            };
            if (channel == null) return;

            String retryReason = null;
            while (true) {
                if (!"sending".equals(campaignRepo.findStatusById(campaignId).orElse(null))) break;
                List<Long> claimed = claimBatch(campaignId, CLAIM_BATCH);
                if (claimed.isEmpty()) break;
                List<CampaignContact> rows = new ArrayList<>(contactRepo.findAllById(claimed));
                rows.sort(Comparator.comparing(CampaignContact::getId));
                Set<String> optedOut = channel.optedOut(rows);
                channel.prepareBatch(rows);

                List<Long> unprocessed = new ArrayList<>(claimed);
                boolean stopped = false;
                int sinceCheck = 0;
                for (CampaignContact cc : rows) {
                    if (++sinceCheck % 10 == 0
                            && !"sending".equals(campaignRepo.findStatusById(campaignId).orElse(null))) {
                        stopped = true;
                        break;
                    }
                    SendResult r;
                    try {
                        r = channel.send(cc, optedOut);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stopped = true;
                        break;
                    } catch (Exception e) {
                        log.warn("Campaign {} recipient {} failed: {}", c.getId(), cc.getId(), e.toString());
                        r = SendResult.of("failed", null, "Send failed");
                    }
                    if (r.retry()) {
                        retryReason = r.reason();
                        stopped = true;
                        break;
                    }
                    contactRepo.markResult(cc.getId(), r.status(), r.messageId(), truncate(r.reason(), 300), Instant.now());
                    unprocessed.remove(cc.getId());
                    if ("sent".equals(r.status())) notSentRuns.remove(campaignId);
                }
                if (stopped) {
                    if (!unprocessed.isEmpty()) contactRepo.releaseClaims(unprocessed);
                    break;
                }
                refreshCounts(campaignId);
            }
            if (retryReason != null) onNothingSent(c, retryReason);
        } finally {
            dispatching.remove(campaignId);
            refreshCounts(campaignId);
            completeIfDone(campaignId);
        }
    }

    /** A run ended because nothing could be sent; pause the campaign after repeated runs like that. */
    private void onNothingSent(Campaign c, String reason) {
        int runs = notSentRuns.merge(c.getId(), 1, Integer::sum);
        log.warn("Campaign {} could not send ({}); attempt {}/{} — recipients stay pending",
                c.getId(), reason, runs, MAX_NOT_SENT_RUNS);
        if (runs < MAX_NOT_SENT_RUNS) return;
        notSentRuns.remove(c.getId());
        if (campaignRepo.markPausedIfSending(c.getId(), Instant.now()) == 1) {
            log.error("Campaign {} paused after {} failed send attempts: {}", c.getId(), MAX_NOT_SENT_RUNS, reason);
            Long to = c.getCreatedBy();
            if (to != null) {
                try {
                    notificationService.notify(c.getOrganizationId(), to, "campaign_paused",
                            "Campaign paused: " + c.getName(),
                            reason + " — fix the sender settings, then resume the campaign.", "Campaign", c.getId());
                } catch (Exception e) {
                    log.debug("campaign paused notification failed: {}", e.toString());
                }
            }
        }
    }

    // ── WhatsApp ──────────────────────────────────────────────────────────────

    private Channel whatsAppChannel(Campaign c) {
        WhatsAppAccount account = resolveAccount(c);
        if (account == null || !account.isActive()) {
            log.warn("Campaign {} has no active WhatsApp account; recipients left pending", c.getId());
            return null;
        }
        List<Map<String, Object>> components;
        String language;
        String templateName;
        try {
            components = whatsAppService.parseTemplateComponents(c.getTemplateParams());
            language = WhatsAppService.validateLanguage(c.getTemplateLanguage());
            templateName = WhatsAppService.validateTemplateName(c.getTemplateName());
        } catch (IllegalArgumentException e) {
            log.warn("Campaign {} has invalid template settings: {}", c.getId(), e.getMessage());
            return null;
        }
        return new Channel() {
            @Override public Set<String> optedOut(List<CampaignContact> rows) {
                return consentService.optedOut(c.getOrganizationId(),
                        rows.stream().map(CampaignContact::getNormalizedPhone).toList());
            }
            @Override public SendResult send(CampaignContact cc, Set<String> optedOut) {
                String normalized = phoneOf(cc);
                if (normalized == null) return SendResult.of("failed", null, "Invalid phone number");
                if (optedOut.contains(normalized) || consentService.isOptedOut(c.getOrganizationId(), normalized))
                    return SendResult.of("skipped_opted_out", null, "Recipient opted out of WhatsApp messages");
                WhatsAppMessage msg = whatsAppService.sendCampaignTemplate(account, normalized, templateName,
                        language, components, c.getId());
                boolean failed = "failed".equals(msg.getStatus());
                return SendResult.of(failed ? "failed" : "sent", msg.getWaMessageId(),
                        failed ? msg.getErrorMessage() : null);
            }
        };
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    private Channel emailChannel(Campaign c) {
        EmailAccount account = resolveEmailAccount(c);
        if (account == null || !account.isActive()) {
            log.warn("Campaign {} has no active email account; recipients left pending", c.getId());
            return null;
        }
        if (isBlank(c.getEmailSubject()) || isBlank(c.getEmailBody())) {
            log.warn("Campaign {} has no email subject / body; recipients left pending", c.getId());
            return null;
        }
        User owner = owner(c);
        Map<String, Lead> leads = new HashMap<>();
        Map<String, Contact> contacts = new HashMap<>();
        return new Channel() {
            @Override public Set<String> optedOut(List<CampaignContact> rows) {
                return emailConsentService.optedOut(c.getOrganizationId(),
                        rows.stream().map(CampaignContact::getEmailNormalized).toList());
            }
            @Override public void prepareBatch(List<CampaignContact> rows) {
                loadRecords(c, rows, leads, contacts);
            }
            @Override public SendResult send(CampaignContact cc, Set<String> optedOut) throws InterruptedException {
                String email = cc.getEmailNormalized() != null ? cc.getEmailNormalized()
                        : EmailConsentService.normalize(cc.getEmail());
                if (email == null) return SendResult.of("skipped", null, "No email address");
                if (optedOut.contains(email) || emailConsentService.isOptedOut(c.getOrganizationId(), email))
                    return SendResult.of("skipped_opted_out", null, "Recipient unsubscribed from email");

                String unsubscribeUrl = emailConsentService.unsubscribeUrl(c.getOrganizationId(), email, c.getId());
                Map<String, String> vars = recipientVars(c, cc, owner, leads, contacts);
                vars.put("contact.email", email);
                vars.put("unsubscribe.url", unsubscribeUrl != null ? unsubscribeUrl : "");
                String subject = AiAutomationService.resolve(c.getEmailSubject(), vars);
                String body = AiAutomationService.resolve(c.getEmailBody(), vars)
                        + unsubscribeFooter(unsubscribeUrl, account.getEmailAddress());

                Map<String, String> headers = new LinkedHashMap<>();
                String mailto = "mailto:" + account.getEmailAddress() + "?subject=unsubscribe";
                headers.put("List-Unsubscribe", unsubscribeUrl != null
                        ? "<" + unsubscribeUrl + ">, <" + mailto + ">" : "<" + mailto + ">");
                if (unsubscribeUrl != null) headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
                headers.put("Precedence", "bulk");
                headers.put("X-CRM-Campaign", String.valueOf(c.getId()));

                throttle.acquire("email:" + account.getId(), emailPerMinute);
                try {
                    String messageId = emailService.sendCampaignEmail(account, email, subject, body, headers);
                    return SendResult.of("sent", truncate(messageId, 200), null);
                } catch (IllegalArgumentException e) {
                    // the recipient was validated above, so this is the mailbox configuration (host / port)
                    return SendResult.retry("Mailbox settings are invalid: " + e.getMessage());
                } catch (Exception e) {
                    return switch (EmailIntegrationService.classifyFailure(e)) {
                        case PERMANENT -> SendResult.of("failed", null, "Rejected by the mail server (address undeliverable)");
                        case NOT_SENT -> SendResult.retry("The mail server could not be reached or rejected the login");
                        case UNKNOWN -> {
                            log.warn("Campaign {} email to recipient {} failed: {}", c.getId(), cc.getId(), e.toString());
                            yield SendResult.of("failed", null, "Send failed (no confirmation from the mail server)");
                        }
                    };
                }
            }
        };
    }

    static String unsubscribeFooter(String url, String mailbox) {
        String how = url != null ? "unsubscribe here: " + url
                : "reply to " + mailbox + " with \"unsubscribe\"";
        return "\n\n--\nYou are receiving this email from us. To stop these emails, " + how;
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    private Channel smsChannel(Campaign c) {
        if (!smsSender.isConfigured()) {
            log.warn("Campaign {} is an SMS campaign but SMS is not configured; recipients left pending", c.getId());
            return null;
        }
        if (isBlank(c.getMessageBody())) {
            log.warn("Campaign {} has no SMS message; recipients left pending", c.getId());
            return null;
        }
        User owner = owner(c);
        Map<String, Lead> leads = new HashMap<>();
        Map<String, Contact> contacts = new HashMap<>();
        return new Channel() {
            @Override public Set<String> optedOut(List<CampaignContact> rows) {
                return consentService.optedOut(c.getOrganizationId(),
                        rows.stream().map(CampaignContact::getNormalizedPhone).toList());
            }
            @Override public void prepareBatch(List<CampaignContact> rows) {
                loadRecords(c, rows, leads, contacts);
            }
            @Override public SendResult send(CampaignContact cc, Set<String> optedOut) throws InterruptedException {
                String normalized = phoneOf(cc);
                if (normalized == null) return SendResult.of("failed", null, "Invalid phone number");
                if (optedOut.contains(normalized) || consentService.isOptedOut(c.getOrganizationId(), normalized))
                    return SendResult.of("skipped_opted_out", null, "Recipient opted out (STOP)");
                String text = AiAutomationService.resolve(c.getMessageBody(), recipientVars(c, cc, owner, leads, contacts));
                throttle.acquire("sms", smsPerMinute);
                UUID key = UUID.nameUUIDFromBytes(("campaign-contact:" + cc.getId()).getBytes(StandardCharsets.UTF_8));
                SmsSender.Result r = smsSender.send(normalized, text, key);
                return switch (r.outcome()) {
                    case SENT -> SendResult.of("sent", truncate(r.messageId(), 200), null);
                    case NOT_SENT -> SendResult.retry(r.detail());
                    case FAILED_PERMANENT, UNKNOWN -> SendResult.of("failed", truncate(r.messageId(), 200), r.detail());
                };
            }
        };
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private static String phoneOf(CampaignContact cc) {
        return cc.getNormalizedPhone() != null ? cc.getNormalizedPhone() : PhoneNumbers.tryNormalize(cc.getPhoneNumber());
    }

    private User owner(Campaign c) {
        Long id = c.getOwnerId() != null ? c.getOwnerId() : c.getCreatedBy();
        return id == null ? null : userRepo.findById(id).orElse(null);
    }

    /** Loads the linked leads / contacts of a batch in two queries (org-checked). */
    private void loadRecords(Campaign c, List<CampaignContact> rows, Map<String, Lead> leads, Map<String, Contact> contacts) {
        leads.clear();
        contacts.clear();
        List<Long> leadIds = new ArrayList<>();
        List<Long> contactIds = new ArrayList<>();
        for (CampaignContact cc : rows) {
            if (cc.getRelatedId() == null) continue;
            if ("Lead".equals(cc.getRelatedType())) leadIds.add(cc.getRelatedId());
            else if ("Contact".equals(cc.getRelatedType())) contactIds.add(cc.getRelatedId());
        }
        if (!leadIds.isEmpty()) {
            for (Lead l : leadRepo.findAllById(leadIds)) {
                if (c.getOrganizationId().equals(l.getOrganizationId())) leads.put(String.valueOf(l.getId()), l);
            }
        }
        if (!contactIds.isEmpty()) {
            for (Contact ct : crmContactRepo.findAllById(contactIds)) {
                if (c.getOrganizationId().equals(ct.getOrganizationId())) contacts.put(String.valueOf(ct.getId()), ct);
            }
        }
    }

    /**
     * Template variables for one recipient: contact.name / contact.firstName /
     * contact.email / contact.phone, record.*, owner.*, campaign.name, plus lead.* or
     * contact.* of the linked record. Values are substituted as plain text.
     */
    static Map<String, String> recipientVars(Campaign c, CampaignContact cc, User owner,
                                             Map<String, Lead> leads, Map<String, Contact> contacts) {
        Map<String, String> v = new HashMap<>();
        String name = cc.getName() == null ? "" : cc.getName();
        String email = cc.getEmailNormalized() != null ? cc.getEmailNormalized() : (cc.getEmail() == null ? "" : cc.getEmail());
        String phone = cc.getNormalizedPhone() != null ? cc.getNormalizedPhone() : (cc.getPhoneNumber() == null ? "" : cc.getPhoneNumber());
        v.put("contact.name", name);
        v.put("contact.firstName", firstName(name));
        v.put("contact.email", email);
        v.put("contact.phone", phone);
        v.put("record.name", name);
        v.put("record.firstName", firstName(name));
        v.put("record.email", email);
        v.put("record.phone", phone);
        v.put("owner.name", owner != null && owner.getName() != null ? owner.getName() : "");
        v.put("owner.email", owner != null && owner.getEmail() != null ? owner.getEmail() : "");
        String key = cc.getRelatedId() == null ? null : String.valueOf(cc.getRelatedId());
        if (key != null && "Lead".equals(cc.getRelatedType()) && leads.containsKey(key)) {
            Lead l = leads.get(key);
            v.putAll(SequenceTemplates.forLead(l, owner));
            if (l.getName() != null && !l.getName().isBlank()) {
                v.put("contact.name", l.getName());
                v.put("contact.firstName", firstName(l.getName()));
            }
        } else if (key != null && "Contact".equals(cc.getRelatedType()) && contacts.containsKey(key)) {
            v.putAll(SequenceTemplates.forContact(contacts.get(key), owner));
            v.put("contact.firstName", firstName(v.get("contact.name")));
        }
        if (v.get("contact.email") == null || v.get("contact.email").isBlank()) v.put("contact.email", email);
        v.put("campaign.name", c.getName() == null ? "" : c.getName());
        return v;
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) return "";
        return name.trim().split("\\s+")[0];
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

    /** The campaign's mailbox, else the org's first active one. */
    public EmailAccount resolveEmailAccount(Campaign c) {
        if (c.getEmailAccountId() != null) {
            return emailAccountRepo.findByIdAndOrganizationId(c.getEmailAccountId(), c.getOrganizationId()).orElse(null);
        }
        return emailAccountRepo.findByOrganizationIdAndIsActiveTrue(c.getOrganizationId()).stream()
                .min(Comparator.comparing(EmailAccount::getId)).orElse(null);
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
     * still have pending recipients (after a restart, a rejected submission or a
     * "nothing was sent" failure).
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
                    if (CHANNELS.contains(c.getType())) dispatchAsync(c.getId());
                }
            }
            for (Campaign c : campaignRepo.findByStatusAndTypeIn("sending", CHANNELS)) {
                if (dispatching.contains(c.getId())) continue;
                if (contactRepo.countByCampaignIdAndStatus(c.getId(), "pending") > 0) dispatchAsync(c.getId());
                else completeIfDone(c.getId());
            }
        } catch (Exception e) {
            log.error("Campaign scheduler run failed: {}", e.toString(), e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
