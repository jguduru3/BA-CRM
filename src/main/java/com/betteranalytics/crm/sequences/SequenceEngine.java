package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.service.EmailIntegrationService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.PhoneNumbers;
import com.betteranalytics.crm.service.WhatsAppConsentService;
import com.betteranalytics.crm.service.WhatsAppService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Runs sequence steps and applies Close-style exit / skip rules.
 *
 * Execution of one due enrollment (all DB work in short transactions, the send
 * itself outside any transaction):
 *   1. claim — a batch of due enrollments of active sequences is claimed
 *      (PostgreSQL: SELECT … FOR UPDATE SKIP LOCKED; H2: one conditional UPDATE
 *      per row), so concurrent ticks / instances never pick the same row;
 *   2. prepare (row-locked tx) — re-checks state, refreshes the record's
 *      email/phone snapshot, decides SKIP (missing data, WhatsApp opt-out,
 *      channel skipped after an earlier unrecoverable failure) or SEND, and
 *      writes the unique (enrollment, step) execution row as PENDING. A TASK
 *      step creates the task right here;
 *   3. send (no tx) through {@link SequenceChannelSender} (timeouts live there);
 *   4. complete (row-locked tx) — records SENT/FAILED, adds the channel to
 *      skipped_channels on an unrecoverable failure, advances to the next step.
 * If a worker dies between 2 and 4 the execution row stays PENDING; the stale
 * claim is picked up later, the PENDING row is marked FAILED ("interrupted")
 * and the step is NOT retried — a step is sent at most once.
 *
 * Exit: any inbound WhatsApp message or (non-auto-reply) inbound email from the
 * enrolled record's phone / email marks ACTIVE or PAUSED enrollments RESPONDED.
 */
@Service
public class SequenceEngine {

    private static final Logger log = LoggerFactory.getLogger(SequenceEngine.class);

    static final int CLAIM_BATCH = 20;
    static final int MAX_BATCHES_PER_TICK = 10;
    static final Duration STALE_CLAIM = Duration.ofMinutes(10);
    static final Duration TICK_BUDGET = Duration.ofSeconds(45);
    static final List<String> LIVE = List.of(SequenceEnrollment.ACTIVE, SequenceEnrollment.PAUSED);
    /** Inbound WhatsApp types that are not a person replying. */
    private static final Set<String> NON_REPLY_WA_TYPES = Set.of("system", "unsupported", "ephemeral");

    private final SequenceRepository sequenceRepo;
    private final SequenceStepRepository stepRepo;
    private final SequenceEnrollmentRepository enrollmentRepo;
    private final SequenceStepExecutionRepository execRepo;
    private final SequenceEnrollmentEventRepository eventRepo;
    private final LeadRepository leadRepo;
    private final ContactRepository contactRepo;
    private final UserRepository userRepo;
    private final TaskRepository taskRepo;
    private final EmailAccountRepository emailAccountRepo;
    private final WhatsAppAccountRepository waAccountRepo;
    private final WhatsAppConsentService consentService;
    private final NotificationService notificationService;
    private final SequenceChannelSender sender;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;
    private volatile Boolean postgres;

    public SequenceEngine(SequenceRepository sequenceRepo, SequenceStepRepository stepRepo,
                          SequenceEnrollmentRepository enrollmentRepo, SequenceStepExecutionRepository execRepo,
                          SequenceEnrollmentEventRepository eventRepo, LeadRepository leadRepo,
                          ContactRepository contactRepo, UserRepository userRepo, TaskRepository taskRepo,
                          EmailAccountRepository emailAccountRepo, WhatsAppAccountRepository waAccountRepo,
                          WhatsAppConsentService consentService, NotificationService notificationService,
                          SequenceChannelSender sender, ObjectMapper objectMapper, JdbcTemplate jdbc,
                          DataSource dataSource, PlatformTransactionManager txManager) {
        this.sequenceRepo = sequenceRepo;
        this.stepRepo = stepRepo;
        this.enrollmentRepo = enrollmentRepo;
        this.execRepo = execRepo;
        this.eventRepo = eventRepo;
        this.leadRepo = leadRepo;
        this.contactRepo = contactRepo;
        this.userRepo = userRepo;
        this.taskRepo = taskRepo;
        this.emailAccountRepo = emailAccountRepo;
        this.waAccountRepo = waAccountRepo;
        this.consentService = consentService;
        this.notificationService = notificationService;
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = new TransactionTemplate(txManager);
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── Tick ────────────────────────────────────────────────────────────────

    /** Claims and runs due enrollments in small batches; returns how many were processed. */
    public int tick() {
        long deadline = System.nanoTime() + TICK_BUDGET.toNanos();
        int processed = 0;
        for (int b = 0; b < MAX_BATCHES_PER_TICK && System.nanoTime() < deadline; b++) {
            List<Long> ids = claimDue(CLAIM_BATCH);
            if (ids.isEmpty()) break;
            for (Long id : ids) {
                processClaimed(id);
                processed++;
            }
        }
        return processed;
    }

    /** Claims up to {@code limit} due enrollments in one short transaction. */
    List<Long> claimDue(int limit) {
        Instant now = Instant.now();
        Instant stale = now.minus(STALE_CLAIM);
        if (isPostgres()) {
            List<Long> ids = tx.execute(s -> {
                List<Long> locked = jdbc.queryForList(
                        "SELECT id FROM sequence_enrollments WHERE status = 'ACTIVE' AND next_run_at <= ? " +
                        "AND (claimed_at IS NULL OR claimed_at < ?) " +
                        "AND sequence_id IN (SELECT id FROM sequences WHERE active = TRUE AND deleted_at IS NULL) " +
                        "ORDER BY next_run_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                        Long.class, utc(now), utc(stale), limit);
                if (locked.isEmpty()) return locked;
                String in = String.join(",", Collections.nCopies(locked.size(), "?"));
                List<Object> args = new ArrayList<>();
                args.add(utc(now));
                args.addAll(locked);
                jdbc.update("UPDATE sequence_enrollments SET claimed_at = ? WHERE id IN (" + in + ")", args.toArray());
                return locked;
            });
            return ids == null ? List.of() : ids;
        }
        List<Long> claimed = new ArrayList<>();
        for (Long id : enrollmentRepo.findDueIds(now, stale, PageRequest.of(0, limit))) {
            Integer n = tx.execute(s -> enrollmentRepo.claim(id, now, stale));
            if (n != null && n == 1) claimed.add(id);
        }
        return claimed;
    }

    /** Runs the current step of one claimed enrollment. Never throws. */
    public void processClaimed(Long enrollmentId) {
        Prepared p;
        try {
            p = tx.execute(s -> prepare(enrollmentId));
        } catch (Exception e) {
            log.error("Sequence enrollment {} failed while preparing a step: {}", enrollmentId, e.toString());
            markFailed(enrollmentId, "Internal error while preparing the step");
            return;
        }
        if (p == null) return;

        SequenceChannelSender.Result result;
        try {
            result = switch (p.channel) {
                case SequenceStep.EMAIL -> sender.sendEmail(p.emailAccount, p.to, p.subject, p.body);
                case SequenceStep.WHATSAPP -> sender.sendWhatsAppTemplate(p.orgId, p.waAccountId, p.to,
                        p.templateName, p.language, p.components, p.relatedType, p.relatedId, p.ownerId);
                default -> SequenceChannelSender.Result.transientFailure("unknown channel");
            };
            if (result == null) result = SequenceChannelSender.Result.transientFailure("no result from sender");
        } catch (Exception e) {
            log.warn("Sequence step send threw for enrollment {}: {}", enrollmentId, e.toString());
            result = SequenceChannelSender.Result.transientFailure("Send failed");
        }

        SequenceChannelSender.Result r = result;
        try {
            tx.executeWithoutResult(s -> complete(p, r));
        } catch (Exception e) {
            log.error("Sequence enrollment {} could not record step result: {}", enrollmentId, e.toString());
        }
    }

    /** What the send phase needs; built inside the prepare transaction. */
    private record Prepared(Long enrollmentId, Long execId, int stepIndex, String channel, Long orgId,
                            Long ownerId, String relatedType, Long relatedId, EmailAccount emailAccount,
                            Long waAccountId, String to, String subject, String body, String templateName,
                            String language, List<Map<String, Object>> components) {}

    private Prepared prepare(Long enrollmentId) {
        Instant now = Instant.now();
        SequenceEnrollment e = enrollmentRepo.lockById(enrollmentId).orElse(null);
        if (e == null) return null;
        if (!SequenceEnrollment.ACTIVE.equals(e.getStatus()) || e.getClaimedAt() == null) {
            e.setClaimedAt(null);
            return null;
        }
        Sequence seq = sequenceRepo.findById(e.getSequenceId()).orElse(null);
        if (seq == null) {
            finish(e, SequenceEnrollment.STOPPED, "Sequence was deleted", null, now);
            return null;
        }
        List<SequenceStep> steps = stepRepo.findBySequenceIdOrderByStepIndexAsc(seq.getId());
        if (e.getCurrentStep() >= steps.size()) {
            finish(e, SequenceEnrollment.COMPLETED, null, null, now);
            return null;
        }
        TargetRecord rec = loadRecord(e);
        if (rec == null) {
            finish(e, SequenceEnrollment.STOPPED, "The " + e.getRelatedType().toLowerCase(Locale.ROOT) + " was deleted", null, now);
            return null;
        }
        e.setContactEmail(normalizeEmail(rec.email));
        e.setContactPhone(PhoneNumbers.tryNormalize(rec.phone));

        int index = e.getCurrentStep();
        SequenceStep step = steps.get(index);
        String channel = step.getChannel();

        SequenceStepExecution existing = execRepo.findByEnrollmentIdAndStepIndex(e.getId(), index).orElse(null);
        if (existing != null) {
            // Only reachable after a crash between the send and its result: never send twice.
            if (SequenceStepExecution.PENDING.equals(existing.getStatus())) {
                existing.setStatus(SequenceStepExecution.FAILED);
                existing.setDetail("Interrupted before the result was recorded; not retried to avoid a duplicate send");
                existing.setCompletedAt(now);
                event(e, "STEP_FAILED", index, channel, existing.getDetail(), null, now);
            }
            advance(e, steps, now);
            return null;
        }

        User owner = e.getOwnerUserId() == null ? null : userRepo.findById(e.getOwnerUserId()).orElse(null);
        Map<String, String> vars = rec.vars(owner);

        if (SequenceStep.TASK.equals(channel)) {
            Task t = new Task();
            t.setOrganizationId(e.getOrganizationId());
            String title = SequenceTemplates.render(blankTo(step.getSubject(), "Sequence follow-up: {{record.name}}"), vars);
            t.setTitle(truncate(title, 300));
            t.setPriority("Medium");
            t.setStatus("Todo");
            t.setAssigneeId(e.getOwnerUserId() != null ? e.getOwnerUserId() : e.getEnrolledBy());
            t.setRelatedType(e.getRelatedType());
            t.setRelatedId(e.getRelatedId());
            t.setDueDate(LocalDate.now(ZoneOffset.UTC));
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            t = taskRepo.save(t);
            SequenceStepExecution x = newExec(e, step, SequenceStepExecution.SENT, "Task created: " + t.getTitle(), now);
            x.setTaskId(t.getId());
            x.setCompletedAt(now);
            execRepo.save(x);
            event(e, "STEP_SENT", index, channel, "Task created: " + t.getTitle(), null, now);
            if (t.getAssigneeId() != null) {
                notificationService.notifyTaskAssigned(e.getOrganizationId(), t.getAssigneeId(), t.getTitle(), t.getId());
            }
            advance(e, steps, now);
            return null;
        }

        String skip = null;
        Prepared p = null;
        if (e.skippedChannelSet().contains(channel)) {
            skip = channelLabel(channel) + " skipped after an earlier unrecoverable failure";
        } else if (SequenceStep.EMAIL.equals(channel)) {
            EmailAccount account = resolveEmailAccount(seq, e);
            if (e.getContactEmail() == null) skip = "No email address on the record";
            else if (account == null) skip = "No active sending mailbox for this sequence";
            else {
                String subject = SequenceTemplates.render(blankTo(step.getSubject(), ""), vars);
                String body = SequenceTemplates.render(blankTo(step.getBody(), ""), vars);
                p = new Prepared(e.getId(), null, index, channel, e.getOrganizationId(), e.getOwnerUserId(),
                        e.getRelatedType(), e.getRelatedId(), account, null, e.getContactEmail(), subject, body,
                        null, null, null);
            }
        } else if (SequenceStep.WHATSAPP.equals(channel)) {
            WhatsAppAccount account = resolveWaAccount(seq);
            if (e.getContactPhone() == null) skip = "No valid phone number on the record";
            else if (consentService.isOptedOut(e.getOrganizationId(), e.getContactPhone())) skip = "Opted out of WhatsApp";
            else if (account == null) skip = "No active WhatsApp sender account";
            else {
                p = new Prepared(e.getId(), null, index, channel, e.getOrganizationId(), e.getOwnerUserId(),
                        e.getRelatedType(), e.getRelatedId(), null, account.getId(), e.getContactPhone(), null, null,
                        step.getWaTemplateName(), WhatsAppService.validateLanguage(step.getWaTemplateLanguage()),
                        components(step.getWaTemplateParams(), vars));
            }
        } else {
            skip = "Unknown channel " + channel;
        }

        if (skip != null) {
            SequenceStepExecution x = newExec(e, step, SequenceStepExecution.SKIPPED, skip, now);
            x.setCompletedAt(now);
            execRepo.save(x);
            event(e, "STEP_SKIPPED", index, channel, skip, null, now);
            advance(e, steps, now);
            return null;
        }

        SequenceStepExecution x = execRepo.saveAndFlush(newExec(e, step, SequenceStepExecution.PENDING, null, now));
        e.setUpdatedAt(now);
        return new Prepared(p.enrollmentId, x.getId(), p.stepIndex, p.channel, p.orgId, p.ownerId, p.relatedType,
                p.relatedId, p.emailAccount, p.waAccountId, p.to, p.subject, p.body, p.templateName, p.language,
                p.components);
    }

    private void complete(Prepared p, SequenceChannelSender.Result r) {
        Instant now = Instant.now();
        SequenceEnrollment e = enrollmentRepo.lockById(p.enrollmentId).orElseThrow();
        SequenceStepExecution x = execRepo.findById(p.execId).orElseThrow();
        boolean sent = r.outcome() == SequenceChannelSender.Outcome.SENT;
        x.setStatus(sent ? SequenceStepExecution.SENT : SequenceStepExecution.FAILED);
        x.setDetail(truncate(r.detail(), 500));
        x.setWaMessageId(truncate(r.waMessageId(), 200));
        x.setWhatsappMessageId(r.whatsappMessageId());
        x.setCompletedAt(now);
        event(e, sent ? "STEP_SENT" : "STEP_FAILED", p.stepIndex, p.channel, r.detail(), null, now);
        if (r.outcome() == SequenceChannelSender.Outcome.FAILED_PERMANENT && e.addSkippedChannel(p.channel)) {
            event(e, "CHANNEL_SKIPPED", p.stepIndex, p.channel,
                    "Later " + channelLabel(p.channel) + " steps will be skipped", null, now);
        }
        if (e.getCurrentStep() == p.stepIndex) {
            advance(e, stepRepo.findBySequenceIdOrderByStepIndexAsc(e.getSequenceId()), now);
        } else {
            e.setClaimedAt(null);
            e.setUpdatedAt(now);
        }
    }

    /** Moves past the current step: schedules the next one, or completes the enrollment. */
    private void advance(SequenceEnrollment e, List<SequenceStep> steps, Instant now) {
        e.setCurrentStep(e.getCurrentStep() + 1);
        e.setClaimedAt(null);
        e.setUpdatedAt(now);
        if (!e.isLive()) return;   // responded / stopped while the step was in flight
        if (e.getCurrentStep() >= steps.size()) {
            finish(e, SequenceEnrollment.COMPLETED, null, null, now);
        } else {
            e.setNextRunAt(now.plus(steps.get(e.getCurrentStep()).delay()));
        }
    }

    private void finish(SequenceEnrollment e, String status, String reason, Long actorId, Instant now) {
        e.finish(status, reason, now);
        event(e, status, null, null, reason, actorId, now);
    }

    private void markFailed(Long enrollmentId, String reason) {
        try {
            tx.executeWithoutResult(s -> enrollmentRepo.lockById(enrollmentId).ifPresent(e -> {
                if (e.isLive()) finish(e, SequenceEnrollment.FAILED, reason, null, Instant.now());
            }));
        } catch (Exception ex) {
            log.error("Could not mark sequence enrollment {} failed: {}", enrollmentId, ex.toString());
        }
    }

    // ── Inbound signals (exit / channel skip) ───────────────────────────────

    @EventListener
    public void onWhatsAppInbound(SequenceSignals.WhatsAppInbound s) {
        if (s.normalizedPhone() == null || s.organizationId() == null) return;
        if (s.messageType() != null && NON_REPLY_WA_TYPES.contains(s.messageType())) return;
        safely(() -> {
            for (SequenceEnrollment e : enrollmentRepo.findByOrganizationIdAndContactPhoneAndStatusIn(
                    s.organizationId(), s.normalizedPhone(), LIVE)) {
                markResponded(e.getId(), s.receivedAt(), "Replied on WhatsApp");
            }
        });
    }

    @EventListener
    public void onWhatsAppDeliveryFailed(SequenceSignals.WhatsAppDeliveryFailed s) {
        if (s.waMessageId() == null || s.organizationId() == null) return;
        safely(() -> execRepo.findFirstByOrganizationIdAndWaMessageId(s.organizationId(), s.waMessageId()).ifPresent(x ->
                txNew.executeWithoutResult(st -> {
                    Instant now = Instant.now();
                    SequenceEnrollment e = enrollmentRepo.lockById(x.getEnrollmentId()).orElse(null);
                    if (e == null) return;
                    SequenceStepExecution fresh = execRepo.findById(x.getId()).orElseThrow();
                    String reason = "WhatsApp delivery failed" + (s.reason() != null ? ": " + s.reason() : "");
                    if (!SequenceStepExecution.FAILED.equals(fresh.getStatus())) {
                        fresh.setStatus(SequenceStepExecution.FAILED);
                        fresh.setDetail(truncate(reason, 500));
                        event(e, "STEP_FAILED", fresh.getStepIndex(), fresh.getChannel(), reason, null, now);
                    }
                    if (e.addSkippedChannel(SequenceStep.WHATSAPP)) {
                        e.setUpdatedAt(now);
                        event(e, "CHANNEL_SKIPPED", fresh.getStepIndex(), SequenceStep.WHATSAPP,
                                "Later WhatsApp steps will be skipped", null, now);
                    }
                })));
    }

    @EventListener
    public void onInboundEmail(SequenceSignals.InboundEmail s) {
        if (s.organizationId() == null) return;
        safely(() -> {
            if (s.deliveryReport()) {
                for (String rcpt : s.failedRecipients() == null ? List.<String>of() : s.failedRecipients()) {
                    String addr = normalizeEmail(rcpt);
                    if (addr == null) continue;
                    for (SequenceEnrollment e : enrollmentRepo.findByOrganizationIdAndContactEmailAndStatusIn(
                            s.organizationId(), addr, LIVE)) {
                        txNew.executeWithoutResult(st -> enrollmentRepo.lockById(e.getId()).ifPresent(locked -> {
                            if (locked.isLive() && locked.addSkippedChannel(SequenceStep.EMAIL)) {
                                Instant now = Instant.now();
                                locked.setUpdatedAt(now);
                                event(locked, "CHANNEL_SKIPPED", null, SequenceStep.EMAIL,
                                        "Email bounced; later email steps will be skipped", null, now);
                            }
                        }));
                    }
                }
                return;
            }
            if (s.autoReply()) return;   // out-of-office / auto-responders never count as a reply
            String from = normalizeEmail(s.fromAddress());
            if (from == null) return;
            for (SequenceEnrollment e : enrollmentRepo.findByOrganizationIdAndContactEmailAndStatusIn(
                    s.organizationId(), from, LIVE)) {
                markResponded(e.getId(), s.receivedAt(), "Replied by email");
            }
        });
    }

    private void markResponded(Long enrollmentId, Instant receivedAt, String reason) {
        Instant at = receivedAt != null ? receivedAt : Instant.now();
        SequenceEnrollment done = txNew.execute(st -> {
            SequenceEnrollment e = enrollmentRepo.lockById(enrollmentId).orElse(null);
            if (e == null || !e.isLive()) return null;
            // A message from before the enrollment (e.g. an old email seen on an inbox read) is not a reply to it.
            if (e.getCreatedAt() != null && at.isBefore(e.getCreatedAt())) return null;
            finish(e, SequenceEnrollment.RESPONDED, reason, null, Instant.now());
            return e;
        });
        if (done != null && done.getOwnerUserId() != null) {
            String seqName = sequenceRepo.findById(done.getSequenceId()).map(Sequence::getName).orElse("a sequence");
            try {
                notificationService.notify(done.getOrganizationId(), done.getOwnerUserId(), "sequence_responded",
                        "Reply received — sequence stopped", reason + "; \"" + seqName + "\" will send nothing further.",
                        done.getRelatedType(), done.getRelatedId());
            } catch (Exception ex) {
                log.debug("sequence response notification failed: {}", ex.toString());
            }
        }
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Sequence signal handling failed: {}", e.toString());
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    void event(SequenceEnrollment e, String type, Integer stepIndex, String channel, String detail,
               Long actorId, Instant now) {
        SequenceEnrollmentEvent ev = new SequenceEnrollmentEvent();
        ev.setEnrollmentId(e.getId());
        ev.setOrganizationId(e.getOrganizationId());
        ev.setEventType(type);
        ev.setStepIndex(stepIndex);
        ev.setChannel(channel);
        ev.setDetail(truncate(detail, 500));
        ev.setActorId(actorId);
        ev.setCreatedAt(now);
        eventRepo.save(ev);
    }

    private SequenceStepExecution newExec(SequenceEnrollment e, SequenceStep step, String status, String detail, Instant now) {
        SequenceStepExecution x = new SequenceStepExecution();
        x.setEnrollmentId(e.getId());
        x.setOrganizationId(e.getOrganizationId());
        x.setStepIndex(e.getCurrentStep());
        x.setStepId(step.getId());
        x.setChannel(step.getChannel());
        x.setStatus(status);
        x.setDetail(truncate(detail, 500));
        x.setCreatedAt(now);
        return x;
    }

    /** The enrolled record's contact data and template variables. */
    record TargetRecord(String name, String email, String phone, Lead lead, Contact contact) {
        Map<String, String> vars(User owner) {
            return lead != null ? SequenceTemplates.forLead(lead, owner) : SequenceTemplates.forContact(contact, owner);
        }
    }

    TargetRecord loadRecord(SequenceEnrollment e) {
        if ("Lead".equals(e.getRelatedType())) {
            return leadRepo.findByIdAndOrganizationId(e.getRelatedId(), e.getOrganizationId())
                    .map(l -> new TargetRecord(l.getName(), l.getEmail(), l.getPhone(), l, null)).orElse(null);
        }
        if ("Contact".equals(e.getRelatedType())) {
            return contactRepo.findByIdAndOrganizationId(e.getRelatedId(), e.getOrganizationId())
                    .map(c -> new TargetRecord(c.getName(), c.getEmail(), c.getPhone(), null, c)).orElse(null);
        }
        return null;
    }

    private EmailAccount resolveEmailAccount(Sequence seq, SequenceEnrollment e) {
        if (seq.getEmailAccountId() != null) {
            return emailAccountRepo.findByIdAndOrganizationId(seq.getEmailAccountId(), seq.getOrganizationId())
                    .filter(EmailAccount::isActive).orElse(null);
        }
        Long owner = e.getOwnerUserId() != null ? e.getOwnerUserId() : e.getEnrolledBy();
        if (owner == null) return null;
        return emailAccountRepo.findByUserId(owner).stream()
                .filter(a -> a.isActive() && seq.getOrganizationId().equals(a.getOrganizationId()))
                .findFirst().orElse(null);
    }

    private WhatsAppAccount resolveWaAccount(Sequence seq) {
        if (seq.getWhatsappAccountId() != null) {
            return waAccountRepo.findByIdAndOrganizationId(seq.getWhatsappAccountId(), seq.getOrganizationId())
                    .filter(WhatsAppAccount::isActive).orElse(null);
        }
        return waAccountRepo.findFirstByOrganizationIdAndActiveTrue(seq.getOrganizationId()).orElse(null);
    }

    /** Body parameters (JSON array of strings, each rendered) → Graph API template components. */
    private List<Map<String, Object>> components(String paramsJson, Map<String, String> vars) {
        List<String> params = parseParams(objectMapper, paramsJson);
        if (params.isEmpty()) return null;
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (String p : params) parameters.add(Map.of("type", "text", "text", SequenceTemplates.render(p, vars)));
        return List.of(Map.of("type", "body", "parameters", parameters));
    }

    static List<String> parseParams(ObjectMapper om, String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> l = om.readValue(json, new TypeReference<List<String>>() {});
            return l == null ? List.of() : l;
        } catch (Exception e) {
            throw new IllegalArgumentException("waTemplateParams must be a JSON array of strings");
        }
    }

    static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return EmailIntegrationService.requireSingleAddress(raw).getAddress().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String channelLabel(String channel) {
        return switch (channel) {
            case SequenceStep.EMAIL -> "Email";
            case SequenceStep.WHATSAPP -> "WhatsApp";
            default -> channel;
        };
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** TIMESTAMP columns hold UTC wall-clock time (hibernate.jdbc.time_zone=UTC). */
    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
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
}
