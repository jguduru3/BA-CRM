package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Campaign lifecycle.
 *
 * Status flow (illegal transitions → 409):
 *   draft → submitted → approved | rejected
 *   rejected → draft (edit) | submitted
 *   approved → scheduled (future scheduledAt) | sending
 *   scheduled → sending (scheduler)          sending → completed (dispatcher)
 *   sending ⇄ paused                          draft…paused → cancelled
 * Only SUPER_ADMIN may launch straight from draft. Approve / reject: SUPER_ADMIN or
 * VP_SALES, never on a campaign you created unless you are SUPER_ADMIN; reject needs a reason.
 * Concurrent changes are caught by Campaign.@Version (409); clients may also send the
 * "version" they last saw (PUT body or action body) to get a 409 on stale edits.
 *
 * GET    /api/campaigns                          — list (?status=, ?page=, ?limit=1..200)
 * POST   /api/campaigns                          — create (draft)
 * GET    /api/campaigns/{id}                     — campaign + first 200 recipients + progress
 * GET    /api/campaigns/{id}/progress            — live counters by recipient status
 * PUT    /api/campaigns/{id}                     — update (draft / rejected)
 * DELETE /api/campaigns/{id}                     — delete (draft / rejected)
 * POST   /api/campaigns/{id}/submit|approve|reject|launch|pause|resume|cancel
 * GET    /api/campaigns/{id}/contacts            — recipients (?status=, ?page=, ?size=1..200)
 * POST   /api/campaigns/{id}/contacts            — add recipients (max 5000 per request)
 * DELETE /api/campaigns/{id}/contacts/{ccId}     — remove recipient (draft / rejected)
 * POST   /api/campaigns/{id}/audience/preview    — count + first 50 recipients for the filters
 * POST   /api/campaigns/{id}/audience/apply      — add every matching lead as a recipient
 * GET    /api/campaigns/channels                 — which channels can send right now
 *
 * Channels: whatsapp (approved template via a WhatsApp account), email (subject +
 * plain-text body via an org mailbox, List-Unsubscribe, email opt-outs honoured),
 * sms (messageBody via Azure Communication Services; 409 "SMS is not configured"
 * on launch when AZURE_COMMUNICATION_CONNECTION_STRING / AZURE_SMS_FROM are unset).
 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "draft",     Set.of("submitted", "scheduled", "sending", "cancelled"),
            "submitted", Set.of("approved", "rejected", "draft", "cancelled"),
            "rejected",  Set.of("draft", "submitted", "cancelled"),
            "approved",  Set.of("scheduled", "sending", "cancelled"),
            "scheduled", Set.of("sending", "cancelled"),
            "sending",   Set.of("paused", "completed", "cancelled"),
            "paused",    Set.of("sending", "cancelled")
    );
    /** Old status names still accepted as filters (rows were renamed by V30). */
    private static final Map<String, String> LEGACY_STATUS = Map.of("running", "sending", "pending_approval", "submitted");
    private static final Set<String> EDITABLE = Set.of("draft", "rejected");
    private static final Set<String> TYPES = Set.of("whatsapp", "email", "sms");
    private static final Set<String> RELATED_TYPES = Set.of("Lead", "Contact");
    private static final int MAX_CONTACTS_PER_REQUEST = 5000;
    private static final int PREVIEW_SIZE = 50;
    private static final int APPLY_PAGE = 500;
    private static final int APPLY_MAX = 50_000;
    private static final int MAX_EMAIL_BODY = 100_000;
    private static final int MAX_SMS_BODY = 1_600;

    private final CampaignRepository        campaignRepo;
    private final CampaignContactRepository contactRepo;
    private final WhatsAppAccountRepository waAccountRepo;
    private final LeadRepository            leadRepo;
    private final ContactRepository         crmContactRepo;
    private final WhatsAppService           whatsAppService;
    private final CampaignDispatcher        dispatcher;
    private final CampaignAudienceService   audienceService;
    private final WhatsAppConsentService    consentService;
    private final AiAutomationService       aiAutomationService;
    private final PermissionService         permissionService;
    private final CurrentUser               currentUser;
    private final NotificationService       notificationService;
    private final AuditService              auditService;
    private final ObjectMapper              objectMapper;
    private final EmailAccountRepository    emailAccountRepo;
    private final EmailConsentService       emailConsentService;
    private final SmsSender                 smsSender;

    public CampaignController(CampaignRepository campaignRepo,
                              CampaignContactRepository contactRepo,
                              WhatsAppAccountRepository waAccountRepo,
                              LeadRepository leadRepo,
                              ContactRepository crmContactRepo,
                              WhatsAppService whatsAppService,
                              CampaignDispatcher dispatcher,
                              CampaignAudienceService audienceService,
                              WhatsAppConsentService consentService,
                              AiAutomationService aiAutomationService,
                              PermissionService permissionService,
                              CurrentUser currentUser,
                              NotificationService notificationService,
                              AuditService auditService,
                              ObjectMapper objectMapper,
                              EmailAccountRepository emailAccountRepo,
                              EmailConsentService emailConsentService,
                              SmsSender smsSender) {
        this.campaignRepo = campaignRepo;
        this.contactRepo = contactRepo;
        this.waAccountRepo = waAccountRepo;
        this.leadRepo = leadRepo;
        this.crmContactRepo = crmContactRepo;
        this.whatsAppService = whatsAppService;
        this.dispatcher = dispatcher;
        this.audienceService = audienceService;
        this.consentService = consentService;
        this.aiAutomationService = aiAutomationService;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.emailAccountRepo = emailAccountRepo;
        this.emailConsentService = emailConsentService;
        this.smsSender = smsSender;
    }

    /** Which channels can send for the caller's org: {whatsapp|email|sms: {configured, detail}}. */
    @GetMapping("/channels")
    public Map<String, Object> channels() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Long org = actor.getOrganizationId();
        boolean wa = waAccountRepo.findFirstByOrganizationIdAndActiveTrue(org).isPresent();
        int mailboxes = emailAccountRepo.findByOrganizationIdAndIsActiveTrue(org).size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("whatsapp", channel(wa, wa ? "Connected WhatsApp sender" : "Connect a WhatsApp account first"));
        out.put("email", channel(mailboxes > 0, mailboxes > 0 ? mailboxes + " connected mailbox" + (mailboxes == 1 ? "" : "es")
                : "Connect an email account (Automations → Email accounts) first"));
        Map<String, Object> sms = channel(smsSender.isConfigured(), smsSender.isConfigured()
                ? "Azure Communication Services, sender " + smsSender.fromNumber()
                : "SMS is not configured (AZURE_COMMUNICATION_CONNECTION_STRING and AZURE_SMS_FROM)");
        sms.put("from", smsSender.fromNumber());
        out.put("sms", sms);
        out.put("emailPerMinute", dispatcher.emailPerMinute());
        return out;
    }

    private static Map<String, Object> channel(boolean configured, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", configured);
        m.put("detail", detail);
        return m;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Campaign>> list(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "200") int limit) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        PageRequest pr = PageRequest.of(Math.max(0, page), clamp(limit));
        if (status != null && !status.isBlank()) {
            String s = LEGACY_STATUS.getOrDefault(status, status);
            return ResponseEntity.ok(campaignRepo.findByOrganizationIdAndStatusOrderByCreatedAtDesc(actor.getOrganizationId(), s, pr));
        }
        return ResponseEntity.ok(campaignRepo.findByOrganizationIdOrderByCreatedAtDesc(actor.getOrganizationId(), pr));
    }

    @PostMapping
    public ResponseEntity<Campaign> create(@RequestBody Campaign body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);

        // Built from allow-listed fields: the client can never set id / organizationId /
        // status / approval fields / counters / version / timestamps.
        Campaign c = new Campaign();
        c.setOrganizationId(actor.getOrganizationId());
        c.setName(requireName(body.getName()));
        String type = body.getType() != null ? body.getType() : "whatsapp";
        if (!TYPES.contains(type)) throw new IllegalArgumentException("type must be whatsapp, email or sms");
        c.setType(type);
        applyEditableFields(c, body, actor.getOrganizationId());
        c.setCreatedBy(actor.getId());
        c.setOwnerId(actor.getId());
        c.setStatus("draft");
        c.setTotalContacts(0);
        c.setSentCount(0);
        c.setDeliveredCount(0);
        c.setReadCount(0);
        c.setFailedCount(0);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        Campaign saved = campaignRepo.save(c);
        auditService.log("create", "campaigns", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campaign", c);
        m.put("contacts", contactRepo.findByCampaignIdAndOrganizationIdOrderByIdAsc(id, actor.getOrganizationId(), PageRequest.of(0, 200)));
        m.put("progress", progressView(c));
        return ResponseEntity.ok(m);
    }

    @GetMapping("/{id}/progress")
    public ResponseEntity<Map<String, Object>> progress(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(progressView(c));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Campaign> update(@PathVariable Long id, @RequestBody Campaign body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        checkVersion(c, body.getVersion());
        requireEditable(c);
        if (body.getName() != null) c.setName(requireName(body.getName()));
        applyEditableFields(c, body, actor.getOrganizationId());
        if ("rejected".equals(c.getStatus())) transition(c, "draft");   // edited after rejection → back to draft
        c.setUpdatedAt(Instant.now());
        Campaign saved = campaignRepo.saveAndFlush(c);
        auditService.log("update", "campaigns", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        requireEditable(c);
        campaignRepo.delete(c);
        auditService.log("delete", "campaigns", c.getId(), c.getName(), c, null);
        return ResponseEntity.noContent().build();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** draft / rejected → submitted. */
    @PostMapping("/{id}/submit")
    public ResponseEntity<Campaign> submit(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        checkVersion(c, versionFrom(body));
        if ("whatsapp".equals(c.getType()) && (c.getTemplateName() == null || c.getTemplateName().isBlank()))
            throw new IllegalArgumentException("A WhatsApp campaign needs a templateName before it can be submitted");
        if ("email".equals(c.getType()) && (blank(c.getEmailSubject()) || blank(c.getEmailBody())))
            throw new IllegalArgumentException("An email campaign needs an emailSubject and emailBody before it can be submitted");
        if ("sms".equals(c.getType()) && blank(c.getMessageBody()))
            throw new IllegalArgumentException("An SMS campaign needs a messageBody before it can be submitted");
        transition(c, "submitted");
        c.setSubmittedAt(Instant.now());
        c.setApprovedBy(null);
        c.setApprovedAt(null);
        Campaign saved = campaignRepo.saveAndFlush(c);
        auditService.log("submit", "campaigns", c.getId(), c.getName(), null, Map.of("submittedBy", actor.getId()));
        notificationService.notifyAdmins(actor.getOrganizationId(), "approval_requested",
                "Campaign pending approval: " + c.getName(),
                "Submitted by " + actor.getName(), "Campaign", c.getId());
        return ResponseEntity.ok(saved);
    }

    /** submitted → approved. SUPER_ADMIN / VP_SALES; not your own campaign unless SUPER_ADMIN. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Campaign> approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        Campaign c = requireReviewable(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        checkVersion(c, versionFrom(body));
        transition(c, "approved");
        c.setApprovedBy(actor.getId());
        c.setApprovedAt(Instant.now());
        c.setRejectionReason(null);
        Campaign saved = campaignRepo.saveAndFlush(c);
        auditService.log("approve", "campaigns", c.getId(), c.getName(), null, Map.of("approvedBy", actor.getId()));
        if (c.getCreatedBy() != null && !c.getCreatedBy().equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), c.getCreatedBy(),
                    "campaign_approved", "Campaign approved: " + c.getName(),
                    "Approved by " + actor.getName(), "Campaign", c.getId());
        }
        Map<String, String> ctx = new HashMap<>();
        ctx.put("campaign.id", String.valueOf(c.getId()));
        ctx.put("campaign.name", c.getName());
        ctx.put("campaign.type", c.getType());
        aiAutomationService.fireEvent(actor.getOrganizationId(), "on_campaign_approved", ctx);
        return ResponseEntity.ok(saved);
    }

    /** submitted → rejected, with a required reason. Same role rule as approve. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Campaign> reject(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        Campaign c = requireReviewable(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        checkVersion(c, versionFrom(body));
        String reason = body != null && body.get("reason") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (reason == null) throw new IllegalArgumentException("A reason is required to reject a campaign");
        if (reason.length() > 2000) reason = reason.substring(0, 2000);
        transition(c, "rejected");
        c.setRejectionReason(reason);
        c.setApprovedBy(null);
        c.setApprovedAt(null);
        Campaign saved = campaignRepo.saveAndFlush(c);
        auditService.log("reject", "campaigns", c.getId(), c.getName(), null,
                Map.of("rejectedBy", actor.getId(), "reason", reason));
        if (c.getCreatedBy() != null && !c.getCreatedBy().equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), c.getCreatedBy(),
                    "campaign_rejected", "Campaign rejected: " + c.getName(), reason, "Campaign", c.getId());
        }
        return ResponseEntity.ok(saved);
    }

    /** approved → scheduled / sending. From draft only SUPER_ADMIN (skips approval). */
    @PostMapping("/{id}/launch")
    @Transactional
    public ResponseEntity<Campaign> launch(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        checkVersion(c, versionFrom(body));

        if ("draft".equals(c.getStatus())) {
            if (actor.getRole() != Role.SUPER_ADMIN)
                throw new AccessDeniedException("Campaign must be approved before launch (only SUPER_ADMIN may launch a draft)");
        } else if (!"approved".equals(c.getStatus())) {
            throw new IllegalStateException("Campaign must be approved before launch, got '" + c.getStatus() + "'");
        }
        if ("whatsapp".equals(c.getType())) requireWhatsAppReady(c);
        else if ("email".equals(c.getType())) requireEmailReady(c);
        else if ("sms".equals(c.getType())) requireSmsReady(c);

        String target = c.getScheduledAt() != null && c.getScheduledAt().isAfter(Instant.now()) ? "scheduled" : "sending";
        transition(c, target);
        Campaign saved = campaignRepo.saveAndFlush(c);
        auditService.log("launch", "campaigns", c.getId(), c.getName(), null,
                Map.of("launchedBy", actor.getId(), "status", target));
        if ("sending".equals(target) && CampaignDispatcher.CHANNELS.contains(c.getType())) {
            dispatcher.dispatchAsync(c.getId());   // after commit, on the bounded executor
        }
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Campaign> pause(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return simpleTransition(id, body, "paused", "pause");
    }

    @PostMapping("/{id}/resume")
    @Transactional
    public ResponseEntity<Campaign> resume(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        Campaign pre = find(id, actor);
        if (pre != null && "sms".equals(pre.getType()) && "paused".equals(pre.getStatus())) requireSmsReady(pre);
        ResponseEntity<Campaign> r = simpleTransition(id, body, "sending", "resume");
        Campaign c = r.getBody();
        if (c != null && CampaignDispatcher.CHANNELS.contains(c.getType())) dispatcher.dispatchAsync(c.getId());
        return r;
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Campaign> cancel(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return simpleTransition(id, body, "cancelled", "cancel");
    }

    private ResponseEntity<Campaign> simpleTransition(Long id, Map<String, Object> body, String to, String auditAction) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        checkVersion(c, versionFrom(body));
        String from = c.getStatus();
        transition(c, to);
        Campaign saved = campaignRepo.saveAndFlush(c);
        auditService.log(auditAction, "campaigns", c.getId(), c.getName(),
                Map.of("status", from), Map.of("status", to, "by", actor.getId()));
        return ResponseEntity.ok(saved);
    }

    // ── Recipients ───────────────────────────────────────────────────────────

    @GetMapping("/{id}/contacts")
    public ResponseEntity<List<CampaignContact>> listContacts(@PathVariable Long id,
                                                              @RequestParam(required = false) String status,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "200") int size) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        if (find(id, actor) == null) return ResponseEntity.notFound().build();
        PageRequest pr = PageRequest.of(Math.max(0, page), clamp(size));
        if (status != null && !status.isBlank()) {
            return ResponseEntity.ok(contactRepo.findByCampaignIdAndOrganizationIdAndStatusOrderByIdAsc(
                    id, actor.getOrganizationId(), status, pr));
        }
        return ResponseEntity.ok(contactRepo.findByCampaignIdAndOrganizationIdOrderByIdAsc(id, actor.getOrganizationId(), pr));
    }

    @PostMapping("/{id}/contacts")
    @Transactional
    public ResponseEntity<Map<String, Object>> addContacts(@PathVariable Long id,
                                                           @RequestBody List<Map<String, Object>> entries) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        requireEditable(c);
        if (entries == null) throw new IllegalArgumentException("body must be an array of recipients");
        if (entries.size() > MAX_CONTACTS_PER_REQUEST)
            throw new IllegalArgumentException("At most " + MAX_CONTACTS_PER_REQUEST + " recipients per request");

        Long orgId = actor.getOrganizationId();
        boolean byEmail = "email".equals(c.getType());
        int invalid = 0;
        Map<String, CampaignContact> candidates = new LinkedHashMap<>();
        int duplicates = 0;
        Instant now = Instant.now();
        for (Map<String, Object> e : entries) {
            if (e == null) { invalid++; continue; }
            Object rawPhone = e.get("phoneNumber") != null ? e.get("phoneNumber") : e.get("phone");
            String phone = rawPhone instanceof String p ? PhoneNumbers.tryNormalize(p) : null;
            String rawEmail = e.get("email") instanceof String m && !m.isBlank() ? m.trim() : null;
            String email = EmailConsentService.normalize(rawEmail);
            String rt = null;
            Long rid = null;
            if (e.get("relatedType") != null || e.get("relatedId") != null) {
                if (!(e.get("relatedType") instanceof String t) || !RELATED_TYPES.contains(t))
                    throw new IllegalArgumentException("relatedType must be Lead or Contact");
                rt = t;
                rid = e.get("relatedId") instanceof Number num && !(num instanceof Double) && !(num instanceof Float)
                        ? num.longValue() : null;
                if (rid == null) throw new IllegalArgumentException("relatedId must be an integer id");
                String[] recordContact = recordContact(rt, rid, orgId);
                if (recordContact == null) throw new IllegalArgumentException(rt + " " + rid + " not found");
                // Missing address -> the linked record's own phone / email.
                if (rawPhone == null) phone = PhoneNumbers.tryNormalize(recordContact[0]);
                if (rawEmail == null) { rawEmail = recordContact[1]; email = EmailConsentService.normalize(rawEmail); }
            }
            String key = byEmail ? email : phone;
            if (key == null) { invalid++; continue; }
            if (candidates.containsKey(key)) { duplicates++; continue; }

            CampaignContact cc = newRecipient(c, phone, byEmail ? rawEmail : null, byEmail ? email : null, now);
            if (e.get("name") instanceof String n && !n.isBlank())
                cc.setName(n.length() > 200 ? n.substring(0, 200) : n);
            cc.setRelatedType(rt);
            cc.setRelatedId(rid);
            candidates.put(key, cc);
        }
        Set<String> existing = new HashSet<>();
        List<String> keys = new ArrayList<>(candidates.keySet());
        for (int i = 0; i < keys.size(); i += 500) {
            List<String> chunk = keys.subList(i, Math.min(keys.size(), i + 500));
            existing.addAll(byEmail ? contactRepo.findExistingEmails(id, chunk) : contactRepo.findExistingPhones(id, chunk));
        }
        List<CampaignContact> toSave = new ArrayList<>();
        for (Map.Entry<String, CampaignContact> en : candidates.entrySet()) {
            if (existing.contains(en.getKey())) duplicates++;
            else toSave.add(en.getValue());
        }
        int optedOut = byEmail
                ? emailConsentService.optedOut(orgId, toSave.stream().map(CampaignContact::getEmailNormalized).toList()).size()
                : consentService.optedOut(orgId, toSave.stream().map(CampaignContact::getNormalizedPhone).toList()).size();
        try {
            contactRepo.saveAll(toSave);
            contactRepo.flush();
        } catch (DataIntegrityViolationException race) {
            throw new IllegalStateException("Recipients were added concurrently — reload and try again");
        }
        c.setTotalContacts((int) contactRepo.countByCampaignId(id));
        c.setUpdatedAt(now);
        campaignRepo.save(c);
        auditService.log("add_recipients", "campaigns", c.getId(), c.getName(), null,
                Map.of("added", toSave.size(), "invalid", invalid, "duplicates", duplicates));
        return ResponseEntity.ok(Map.of("added", toSave.size(), "invalid", invalid,
                "duplicates", duplicates, "optedOut", optedOut, "total", c.getTotalContacts()));
    }

    @DeleteMapping("/{id}/contacts/{ccId}")
    @Transactional
    public ResponseEntity<Void> removeContact(@PathVariable Long id, @PathVariable Long ccId) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        requireEditable(c);
        CampaignContact cc = contactRepo.findByIdAndOrganizationId(ccId, actor.getOrganizationId()).orElse(null);
        if (cc == null || !cc.getCampaignId().equals(id)) return ResponseEntity.notFound().build();
        contactRepo.delete(cc);
        contactRepo.flush();
        c.setTotalContacts((int) contactRepo.countByCampaignId(id));
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);
        return ResponseEntity.noContent().build();
    }

    // ── Audience (evaluated in the database) ─────────────────────────────────

    /**
     * Count + first 50 leads (with a phone) for the given criteria — or the
     * campaign's saved audienceCriteriaJson — before anything is submitted.
     */
    @PostMapping("/{id}/audience/preview")
    public ResponseEntity<Map<String, Object>> previewAudience(@PathVariable Long id,
                                                               @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        Long orgId = actor.getOrganizationId();
        List<CampaignAudienceService.Filter> filters = audienceService.parse(criteriaFrom(body, c), false);
        boolean byEmail = "email".equals(c.getType());
        String field = CampaignAudienceService.requiredField(c.getType());

        long matching = audienceService.count(orgId, filters, (String) null);
        long withField = audienceService.count(orgId, filters, field);
        List<CampaignAudienceService.LeadRow> rows = audienceService.page(orgId, filters, null, PREVIEW_SIZE, field);
        Set<String> optedOut = byEmail
                ? emailConsentService.optedOut(orgId, rows.stream().map(r -> EmailConsentService.normalize(r.email())).filter(Objects::nonNull).toList())
                : consentService.optedOut(orgId, rows.stream().map(r -> PhoneNumbers.tryNormalize(r.phone())).filter(Objects::nonNull).toList());
        List<Map<String, Object>> recipients = new ArrayList<>();
        for (CampaignAudienceService.LeadRow r : rows) {
            String normalized = PhoneNumbers.tryNormalize(r.phone());
            String email = EmailConsentService.normalize(r.email());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relatedType", "Lead");
            m.put("relatedId", r.id());
            m.put("name", r.name());
            m.put("phone", r.phone());
            m.put("normalizedPhone", normalized);
            m.put("validPhone", normalized != null);
            m.put("email", r.email());
            m.put("validEmail", email != null);
            String key = byEmail ? email : normalized;
            m.put("optedOut", key != null && optedOut.contains(key));
            recipients.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channelField", field);
        out.put("count", withField);
        out.put("matching", matching);
        out.put("withoutPhone", byEmail ? matching - audienceService.count(orgId, filters, "phone") : matching - withField);
        out.put("withoutEmail", byEmail ? matching - withField : matching - audienceService.count(orgId, filters, "email"));
        out.put("recipients", recipients);
        out.put("truncated", withField > recipients.size());
        return ResponseEntity.ok(out);
    }

    /** Adds every matching lead with a valid phone as a recipient (paged, deduplicated in the DB). */
    @PostMapping("/{id}/audience/apply")
    public ResponseEntity<Map<String, Object>> applyAudience(@PathVariable Long id,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        requireEditable(c);
        Long orgId = actor.getOrganizationId();
        String criteria = criteriaFrom(body, c);
        List<CampaignAudienceService.Filter> filters = audienceService.parse(criteria, false);
        boolean byEmail = "email".equals(c.getType());
        String field = CampaignAudienceService.requiredField(c.getType());

        int added = 0, invalid = 0, duplicates = 0, scanned = 0;
        Long after = null;
        Instant now = Instant.now();
        while (scanned < APPLY_MAX) {
            List<CampaignAudienceService.LeadRow> rows = audienceService.page(orgId, filters, after, APPLY_PAGE, field);
            if (rows.isEmpty()) break;
            after = rows.get(rows.size() - 1).id();
            scanned += rows.size();
            Map<String, CampaignContact> batch = new LinkedHashMap<>();
            for (CampaignAudienceService.LeadRow r : rows) {
                String phone = PhoneNumbers.tryNormalize(r.phone());
                String email = EmailConsentService.normalize(r.email());
                String key = byEmail ? email : phone;
                if (key == null) { invalid++; continue; }
                if (batch.containsKey(key)) { duplicates++; continue; }
                CampaignContact cc = newRecipient(c, phone, byEmail ? r.email().trim() : null, byEmail ? email : null, now);
                cc.setName(r.name() != null && r.name().length() > 200 ? r.name().substring(0, 200) : r.name());
                cc.setRelatedType("Lead");
                cc.setRelatedId(r.id());
                batch.put(key, cc);
            }
            if (batch.isEmpty()) continue;
            Set<String> existing = new HashSet<>(byEmail ? contactRepo.findExistingEmails(id, batch.keySet())
                    : contactRepo.findExistingPhones(id, batch.keySet()));
            List<CampaignContact> toSave = batch.values().stream()
                    .filter(cc -> !existing.contains(byEmail ? cc.getEmailNormalized() : cc.getNormalizedPhone())).toList();
            duplicates += batch.size() - toSave.size();
            try {
                contactRepo.saveAll(toSave);
                contactRepo.flush();
            } catch (DataIntegrityViolationException race) {
                throw new IllegalStateException("Recipients were added concurrently — reload and try again");
            }
            added += toSave.size();
        }
        Campaign fresh = find(id, actor);
        if (fresh != null) {
            if (criteria != null && !criteria.equals(fresh.getAudienceCriteriaJson())) fresh.setAudienceCriteriaJson(criteria);
            fresh.setTotalContacts((int) contactRepo.countByCampaignId(id));
            fresh.setUpdatedAt(Instant.now());
            campaignRepo.save(fresh);
        }
        auditService.log("apply_audience", "campaigns", c.getId(), c.getName(), null,
                Map.of("added", added, "invalid", invalid, "duplicates", duplicates));
        return ResponseEntity.ok(Map.of("added", added, "invalid", invalid, "duplicates", duplicates,
                "total", fresh != null ? fresh.getTotalContacts() : added, "truncated", scanned >= APPLY_MAX));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Campaign find(Long id, User actor) {
        return campaignRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
    }

    private Campaign requireReviewable(Long id, User actor) {
        if (actor.getRole() != Role.SUPER_ADMIN && actor.getRole() != Role.VP_SALES)
            throw new AccessDeniedException("Only SUPER_ADMIN or VP_SALES can review campaigns");
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return null;
        // Four-eyes: nobody but SUPER_ADMIN reviews their own campaign.
        if (actor.getId().equals(c.getCreatedBy()) && actor.getRole() != Role.SUPER_ADMIN)
            throw new AccessDeniedException("You cannot review a campaign you created");
        return c;
    }

    /** [phone, email] of a Lead / Contact in the org, or null when it does not exist. */
    private String[] recordContact(String relatedType, Long id, Long orgId) {
        if ("Lead".equals(relatedType)) {
            return leadRepo.findByIdAndOrganizationId(id, orgId).map(l -> new String[]{l.getPhone(), l.getEmail()}).orElse(null);
        }
        return crmContactRepo.findByIdAndOrganizationId(id, orgId).map(x -> new String[]{x.getPhone(), x.getEmail()}).orElse(null);
    }

    private CampaignContact newRecipient(Campaign c, String normalizedPhone, String email, String emailNormalized, Instant now) {
        CampaignContact cc = new CampaignContact();
        cc.setCampaignId(c.getId());
        cc.setOrganizationId(c.getOrganizationId());
        cc.setPhoneNumber(normalizedPhone);
        cc.setNormalizedPhone(normalizedPhone);
        cc.setEmail(email == null ? null : (email.length() > 320 ? email.substring(0, 320) : email));
        cc.setEmailNormalized(emailNormalized);
        cc.setStatus("pending");
        cc.setCreatedAt(now);
        cc.setUpdatedAt(now);
        return cc;
    }

    private Map<String, Object> progressView(Campaign c) {
        Map<String, Long> counts = dispatcher.statusCounts(c.getId());
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long open = counts.get("pending") + counts.get("sending");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("status", c.getStatus());
        m.put("version", c.getVersion());
        m.put("total", total);
        m.put("processed", total - open);
        m.put("percent", total == 0 ? 0 : Math.round((total - open) * 1000.0 / total) / 10.0);
        m.put("counts", counts);
        m.put("sentCount", c.getSentCount());
        m.put("deliveredCount", c.getDeliveredCount());
        m.put("readCount", c.getReadCount());
        m.put("failedCount", c.getFailedCount());
        m.put("startedAt", c.getStartedAt());
        m.put("completedAt", c.getCompletedAt());
        return m;
    }

    /** Copies the client-editable fields (validated) onto a campaign. Nulls leave fields unchanged. */
    private void applyEditableFields(Campaign c, Campaign body, Long orgId) {
        if (body.getTemplateName() != null)
            c.setTemplateName(body.getTemplateName().isBlank() ? null : WhatsAppService.validateTemplateName(body.getTemplateName().trim()));
        if (body.getTemplateLanguage() != null)
            c.setTemplateLanguage(WhatsAppService.validateLanguage(body.getTemplateLanguage().trim()));
        if (body.getTemplateParams() != null) {
            whatsAppService.parseTemplateComponents(body.getTemplateParams());   // 400 when not a JSON array
            c.setTemplateParams(body.getTemplateParams().isBlank() ? null : body.getTemplateParams());
        }
        if (body.getMessageBody() != null) {
            if ("sms".equals(c.getType()) && body.getMessageBody().length() > MAX_SMS_BODY)
                throw new IllegalArgumentException("messageBody exceeds " + MAX_SMS_BODY + " characters");
            c.setMessageBody(body.getMessageBody());
        }
        if (body.getEmailSubject() != null) {
            String subj = body.getEmailSubject().replaceAll("[\\r\\n]+", " ").trim();
            if (subj.length() > 300) throw new IllegalArgumentException("emailSubject exceeds 300 characters");
            c.setEmailSubject(subj.isEmpty() ? null : subj);
        }
        if (body.getEmailBody() != null) {
            if (body.getEmailBody().length() > MAX_EMAIL_BODY)
                throw new IllegalArgumentException("emailBody exceeds " + MAX_EMAIL_BODY + " characters");
            c.setEmailBody(body.getEmailBody().isBlank() ? null : body.getEmailBody());
        }
        if (body.getEmailAccountId() != null) {
            if (emailAccountRepo.findByIdAndOrganizationId(body.getEmailAccountId(), orgId).isEmpty())
                throw new IllegalArgumentException("emailAccountId does not refer to an email account in your organization");
            c.setEmailAccountId(body.getEmailAccountId());
        }
        if (body.getScheduledAt() != null) c.setScheduledAt(body.getScheduledAt());
        if (body.getAudienceCriteriaJson() != null) {
            if (!body.getAudienceCriteriaJson().isBlank()) audienceService.parse(body.getAudienceCriteriaJson(), true);
            c.setAudienceCriteriaJson(body.getAudienceCriteriaJson().isBlank() ? null : body.getAudienceCriteriaJson());
        }
        if (body.getWaAccountId() != null) {
            if (waAccountRepo.findByIdAndOrganizationId(body.getWaAccountId(), orgId).isEmpty())
                throw new IllegalArgumentException("waAccountId does not refer to a WhatsApp account in your organization");
            c.setWaAccountId(body.getWaAccountId());
        }
    }

    private String criteriaFrom(Map<String, Object> body, Campaign c) {
        if (body != null && body.get("criteriaJson") != null) {
            Object v = body.get("criteriaJson");
            if (v instanceof String s) return s;
            try { return objectMapper.writeValueAsString(v); }
            catch (Exception e) { throw new IllegalArgumentException("criteriaJson must be a JSON object"); }
        }
        return c.getAudienceCriteriaJson();
    }

    private void requireWhatsAppReady(Campaign c) {
        if (c.getTemplateName() == null || c.getTemplateName().isBlank())
            throw new IllegalStateException("A WhatsApp campaign needs a templateName before launch");
        boolean hasAccount = c.getWaAccountId() != null
                ? waAccountRepo.findByIdAndOrganizationId(c.getWaAccountId(), c.getOrganizationId())
                        .map(WhatsAppAccount::isActive).orElse(false)
                : waAccountRepo.findFirstByOrganizationIdAndActiveTrue(c.getOrganizationId()).isPresent();
        if (!hasAccount) throw new IllegalStateException("No active WhatsApp account for this campaign");
    }

    private void requireEmailReady(Campaign c) {
        if (blank(c.getEmailSubject()) || blank(c.getEmailBody()))
            throw new IllegalStateException("An email campaign needs a subject and a body before launch");
        EmailAccount a = dispatcher.resolveEmailAccount(c);
        if (a == null || !a.isActive()) throw new IllegalStateException("No active email account for this campaign");
    }

    private void requireSmsReady(Campaign c) {
        if (!smsSender.isConfigured()) throw new IllegalStateException("SMS is not configured");
        if (blank(c.getMessageBody())) throw new IllegalStateException("An SMS campaign needs a message before launch");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Long versionFrom(Map<String, Object> body) {
        if (body == null || body.get("version") == null) return null;
        if (body.get("version") instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("version must be a number");
    }

    /** Client-supplied version (if any) must match — otherwise someone else changed it first (409). */
    private static void checkVersion(Campaign c, Long expected) {
        if (expected != null && !expected.equals(c.getVersion()))
            throw new OptimisticLockingFailureException(
                    "This campaign was changed by someone else — reload it and try again");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        String n = name.trim();
        if (n.length() > 200) throw new IllegalArgumentException("name exceeds 200 characters");
        return n;
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(200, n));
    }

    private static void transition(Campaign c, String to) {
        String from = c.getStatus();
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to))
            throw new IllegalStateException("Invalid campaign transition: " + from + " → " + to);
        c.setStatus(to);
        c.setUpdatedAt(Instant.now());
        if ("sending".equals(to) && c.getStartedAt() == null) c.setStartedAt(Instant.now());
        if ("completed".equals(to) || "cancelled".equals(to)) c.setCompletedAt(Instant.now());
    }

    private static void requireEditable(Campaign c) {
        if (!EDITABLE.contains(c.getStatus()))
            throw new IllegalStateException("Campaign can only be changed while draft or rejected, got '" + c.getStatus() + "'");
    }
}
