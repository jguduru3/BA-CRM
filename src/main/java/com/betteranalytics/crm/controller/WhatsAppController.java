package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.repository.OpportunityRepository;
import com.betteranalytics.crm.repository.WhatsAppAccountRepository;
import com.betteranalytics.crm.repository.WhatsAppMessageRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.PhoneNumbers;
import com.betteranalytics.crm.service.WhatsAppConsentService;
import com.betteranalytics.crm.service.WhatsAppRecordResolver;
import com.betteranalytics.crm.service.WhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * WhatsApp Cloud API integration endpoints. Every authenticated endpoint goes
 * through PermissionService with ResourceType.WHATSAPP:
 *   CREATE = send a message / record consent, VIEW = conversations (metadata),
 *   EDIT / DELETE = sender accounts.
 * Message bodies are a field mask (PermissionService.canViewWhatsAppBody): others
 * get the message with body = null and bodyHidden = true.
 *
 * GET    /api/whatsapp/webhook                       — Meta verification handshake (public)
 * POST   /api/whatsapp/webhook                       — inbound messages + statuses (public, X-Hub-Signature-256)
 * POST   /api/whatsapp/send                          — text / template from a chosen sender account
 * GET    /api/whatsapp/conversations                 — inbox: one row per number, newest first (?page, ?size)
 * GET    /api/whatsapp/conversations/phone/{phone}   — thread by number (newest first)
 * GET    /api/whatsapp/conversations/{type}/{id}     — thread for a CRM record
 * GET    /api/whatsapp/consent/{phone}               — consent state + history
 * POST   /api/whatsapp/consent                       — manual opt-in / opt-out {phone,status,reason}
 * GET    /api/whatsapp/accounts                      — sender accounts (secrets never returned)
 * POST   /api/whatsapp/accounts | PUT …/{id} | DELETE …/{id}
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppController.class);
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");
    private static final int MAX_TEXT = 4096;

    private final WhatsAppService           whatsAppService;
    private final WhatsAppConsentService    consentService;
    private final WhatsAppRecordResolver    recordResolver;
    private final WhatsAppAccountRepository accountRepo;
    private final WhatsAppMessageRepository messageRepo;
    private final LeadRepository            leadRepo;
    private final ContactRepository         contactRepo;
    private final OpportunityRepository     opportunityRepo;
    private final PermissionService         permissionService;
    private final AuditService              auditService;
    private final CurrentUser               currentUser;

    public WhatsAppController(WhatsAppService whatsAppService,
                              WhatsAppConsentService consentService,
                              WhatsAppRecordResolver recordResolver,
                              WhatsAppAccountRepository accountRepo,
                              WhatsAppMessageRepository messageRepo,
                              LeadRepository leadRepo,
                              ContactRepository contactRepo,
                              OpportunityRepository opportunityRepo,
                              PermissionService permissionService,
                              AuditService auditService,
                              CurrentUser currentUser) {
        this.whatsAppService = whatsAppService;
        this.consentService = consentService;
        this.recordResolver = recordResolver;
        this.accountRepo = accountRepo;
        this.messageRepo = messageRepo;
        this.leadRepo = leadRepo;
        this.contactRepo = contactRepo;
        this.opportunityRepo = opportunityRepo;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    // ── Webhook — PUBLIC endpoints (no JWT, Meta calls these) ────────────────

    @GetMapping("/webhook")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String verifyToken,
            @RequestParam("hub.challenge") String challenge) {
        if ("subscribe".equals(mode) && CHALLENGE.matcher(challenge).matches()
                && whatsAppService.isKnownVerifyToken(verifyToken)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
        }
        return ResponseEntity.status(403).contentType(MediaType.TEXT_PLAIN).body("Forbidden");
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        byte[] body = rawBody != null ? rawBody : new byte[0];
        if (!whatsAppService.isWebhookSignatureValid(body, signature)) {
            return ResponseEntity.status(401).build();
        }
        try {
            whatsAppService.processWebhook(body);
        } catch (Exception e) {
            // Always return 200 for a verified payload so Meta does not retry malformed events forever
            log.warn("WhatsApp webhook payload could not be processed: {}", e.toString());
        }
        return ResponseEntity.ok().build();
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    @PostMapping("/send")
    public ResponseEntity<WhatsAppMessage> send(@RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        Long accountId = asLong(body.get("accountId"), "accountId");
        String phone   = WhatsAppService.normalizePhone(asString(body.get("phone"), "phone"));
        String type    = body.get("type") == null ? "text" : asString(body.get("type"), "type");
        String relType = body.get("relatedType") == null ? null : asString(body.get("relatedType"), "relatedType");
        Long relId     = body.get("relatedId") == null ? null : asLong(body.get("relatedId"), "relatedId");

        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        if ((relType == null) != (relId == null))
            throw new IllegalArgumentException("relatedType and relatedId must be given together");
        // Messaging about a CRM record requires being able to see that record.
        if (relType != null) requireRecordAccess(actor, relType, relId);

        WhatsAppMessage msg;
        if ("template".equals(type)) {
            msg = whatsAppService.sendTemplate(actor.getOrganizationId(), accountId, phone,
                    asString(body.get("templateName"), "templateName"),
                    body.get("templateLanguage") == null ? null : asString(body.get("templateLanguage"), "templateLanguage"),
                    asComponents(body.get("components")),
                    relType, relId, actor.getId());
        } else if ("text".equals(type)) {
            String text = asString(body.get("text"), "text");
            if (text == null || text.isBlank()) throw new IllegalArgumentException("text is required");
            if (text.length() > MAX_TEXT) throw new IllegalArgumentException("text exceeds " + MAX_TEXT + " characters");
            msg = whatsAppService.sendText(actor.getOrganizationId(), accountId, phone, text,
                    relType, relId, actor.getId());
        } else {
            throw new IllegalArgumentException("type must be 'text' or 'template'");
        }
        return ResponseEntity.ok(msg);
    }

    // ── Conversations ─────────────────────────────────────────────────────────

    /** Inbox: latest message per number (computed in the DB), newest conversation first. */
    @GetMapping("/conversations")
    public ResponseEntity<List<Map<String, Object>>> inbox(@RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "30") int size) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        Long orgId = actor.getOrganizationId();
        List<Object[]> threads = messageRepo.findThreads(orgId, PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size))));
        if (threads.isEmpty()) return ResponseEntity.ok(List.of());
        List<String> phones = threads.stream().map(t -> (String) t[0]).toList();
        Map<String, WhatsAppMessage> latest = new HashMap<>();
        for (WhatsAppMessage m : mask(actor, messageRepo.findLatestPerPhone(orgId, phones))) {
            latest.merge(m.getNormalizedPhone(), m, (a, b) -> a.getId() > b.getId() ? a : b);
        }
        Set<String> optedOut = consentService.optedOut(orgId, phones);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] t : threads) {
            String phone = (String) t[0];
            WhatsAppMessage last = latest.get(phone);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phone", phone);
            row.put("lastMessageAt", t[1]);
            row.put("messageCount", t[2]);
            row.put("lastMessage", last);
            row.put("relatedType", last != null ? last.getRelatedType() : null);
            row.put("relatedId", last != null ? last.getRelatedId() : null);
            row.put("optedOut", optedOut.contains(phone));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/conversations/{relatedType}/{relatedId}")
    public ResponseEntity<List<WhatsAppMessage>> conversationByRecord(
            @PathVariable String relatedType, @PathVariable Long relatedId,
            @RequestParam(defaultValue = "200") int limit) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        requireRecordAccess(actor, relatedType, relatedId);
        return ResponseEntity.ok(mask(actor,
                messageRepo.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
                        actor.getOrganizationId(), relatedType, relatedId, PageRequest.of(0, clamp(limit)))));
    }

    @GetMapping("/conversations/phone/{phone}")
    public ResponseEntity<List<WhatsAppMessage>> conversationByPhone(
            @PathVariable String phone, @RequestParam(defaultValue = "50") int limit) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        String normalized = PhoneNumbers.normalize(phone);
        return ResponseEntity.ok(mask(actor,
                messageRepo.findByOrganizationIdAndNormalizedPhoneOrderByCreatedAtDesc(
                        actor.getOrganizationId(), normalized, PageRequest.of(0, clamp(limit)))));
    }

    // ── Consent ───────────────────────────────────────────────────────────────

    @GetMapping("/consent/{phone}")
    public ResponseEntity<Map<String, Object>> consent(@PathVariable String phone) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        String normalized = PhoneNumbers.normalize(phone);
        Map<String, Object> view = new LinkedHashMap<>(consentService.view(actor.getOrganizationId(), normalized));
        view.put("history", consentService.history(actor.getOrganizationId(), normalized, 50));
        return ResponseEntity.ok(view);
    }

    /** Manual opt-in / opt-out; a reason is required (it is kept in the history). */
    @PostMapping("/consent")
    public ResponseEntity<Map<String, Object>> recordConsent(@RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        String normalized = PhoneNumbers.normalize(asString(body.get("phone"), "phone"));
        String status = asString(body.get("status"), "status");
        String reason = asString(body.get("reason"), "reason");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        WhatsAppConsent c = consentService.record(actor.getOrganizationId(), normalized, status,
                WhatsAppConsentService.SOURCE_MANUAL, reason, actor.getId(), null);
        auditService.log("consent_" + c.getStatus(), "whatsapp_consents", c.getId(), normalized, null,
                Map.of("status", c.getStatus(), "reason", c.getReason()));
        Map<String, Object> view = new LinkedHashMap<>(consentService.view(actor.getOrganizationId(), normalized));
        view.put("history", consentService.history(actor.getOrganizationId(), normalized, 50));
        return ResponseEntity.ok(view);
    }

    // ── Sender accounts ───────────────────────────────────────────────────────

    /** Everyone who may view WhatsApp sees the sender list (to pick one); admins also see config flags. */
    @GetMapping("/accounts")
    public ResponseEntity<List<Map<String, Object>>> listAccounts() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        boolean admin = permissionService.can(actor, Action.EDIT, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        return ResponseEntity.ok(accountRepo.findByOrganizationId(actor.getOrganizationId()).stream()
                .sorted(Comparator.comparing(WhatsAppAccount::getId))
                .map(a -> accountView(a, admin)).toList());
    }

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> createAccount(@RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.WHATSAPP, actor.getOrganizationId(), null);

        String phoneNumberId = WhatsAppService.validatePhoneNumberId(asString(body.get("phoneNumberId"), "phoneNumberId"));
        String accessToken   = asString(body.get("accessToken"), "accessToken");
        if (accessToken == null || accessToken.isBlank()) throw new IllegalArgumentException("accessToken is required");
        requirePhoneNumberIdFree(phoneNumberId, null);

        // Built from allow-listed fields only — never trust client id/org/audit fields.
        WhatsAppAccount acct = new WhatsAppAccount();
        acct.setOrganizationId(actor.getOrganizationId());
        acct.setPhoneNumberId(phoneNumberId);
        acct.setBusinessAccountId(optString(body, "businessAccountId", 100));
        acct.setDisplayPhoneNumber(optString(body, "displayPhoneNumber", 30));
        acct.setAccessToken(accessToken.trim());
        acct.setWebhookVerifyToken(optString(body, "webhookVerifyToken", 200));
        acct.setActive(!(body.get("active") instanceof Boolean b) || b);
        acct.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        acct.setCreatedAt(now);
        acct.setUpdatedAt(now);
        WhatsAppAccount saved = saveAccount(acct);
        auditService.log("create", "whatsapp_accounts", saved.getId(), saved.getDisplayPhoneNumber(), null,
                accountView(saved, true));
        return ResponseEntity.ok(accountView(saved, true));
    }

    @PutMapping("/accounts/{id}")
    public ResponseEntity<Map<String, Object>> updateAccount(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        WhatsAppAccount acct = accountRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (acct == null) return ResponseEntity.notFound().build();
        Map<String, Object> before = accountView(acct, true);
        if (body.get("phoneNumberId") != null) {
            String pnid = WhatsAppService.validatePhoneNumberId(asString(body.get("phoneNumberId"), "phoneNumberId"));
            requirePhoneNumberIdFree(pnid, acct.getId());
            acct.setPhoneNumberId(pnid);
        }
        if (body.containsKey("businessAccountId")) acct.setBusinessAccountId(optString(body, "businessAccountId", 100));
        if (body.containsKey("displayPhoneNumber")) acct.setDisplayPhoneNumber(optString(body, "displayPhoneNumber", 30));
        String token = body.get("accessToken") == null ? null : asString(body.get("accessToken"), "accessToken");
        if (token != null && !token.isBlank()) acct.setAccessToken(token.trim());
        String verify = optString(body, "webhookVerifyToken", 200);
        if (verify != null) acct.setWebhookVerifyToken(verify);
        // Only change activation when the client explicitly sends it.
        if (body.get("active") != null) {
            if (!(body.get("active") instanceof Boolean b)) throw new IllegalArgumentException("active must be a boolean");
            acct.setActive(b);
        }
        acct.setUpdatedAt(Instant.now());
        WhatsAppAccount saved = saveAccount(acct);
        auditService.log("update", "whatsapp_accounts", saved.getId(), saved.getDisplayPhoneNumber(), before,
                accountView(saved, true));
        return ResponseEntity.ok(accountView(saved, true));
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.WHATSAPP, actor.getOrganizationId(), null);
        WhatsAppAccount acct = accountRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (acct == null) return ResponseEntity.notFound().build();
        try {
            accountRepo.delete(acct);
            accountRepo.flush();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("This sender has message or campaign history — deactivate it instead");
        }
        auditService.log("delete", "whatsapp_accounts", acct.getId(), acct.getDisplayPhoneNumber(),
                accountView(acct, true), null);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Applies the body field mask to a page of messages. Owners of the linked
     * records are resolved with one query per record type (no N+1). Returns
     * copies — managed entities are never mutated.
     */
    private List<WhatsAppMessage> mask(User actor, List<WhatsAppMessage> messages) {
        Map<String, Set<Long>> refs = new HashMap<>();
        for (WhatsAppMessage m : messages) {
            if (m.getRelatedType() != null && m.getRelatedId() != null)
                refs.computeIfAbsent(m.getRelatedType(), k -> new HashSet<>()).add(m.getRelatedId());
        }
        boolean managerUp = permissionService.canViewWhatsAppBody(actor, null, null);
        Map<String, Map<Long, Long>> owners = managerUp || refs.isEmpty()
                ? Map.of() : recordResolver.owners(actor.getOrganizationId(), refs);
        List<WhatsAppMessage> out = new ArrayList<>(messages.size());
        for (WhatsAppMessage m : messages) {
            Long ownerId = m.getRelatedType() == null ? null
                    : owners.getOrDefault(m.getRelatedType(), Map.of()).get(m.getRelatedId());
            out.add(permissionService.canViewWhatsAppBody(actor, m, ownerId) ? m : hiddenCopy(m));
        }
        return out;
    }

    private static WhatsAppMessage hiddenCopy(WhatsAppMessage m) {
        WhatsAppMessage c = new WhatsAppMessage();
        c.setId(m.getId());
        c.setOrganizationId(m.getOrganizationId());
        c.setAccountId(m.getAccountId());
        c.setDirection(m.getDirection());
        c.setWaMessageId(m.getWaMessageId());
        c.setFromNumber(m.getFromNumber());
        c.setToNumber(m.getToNumber());
        c.setNormalizedPhone(m.getNormalizedPhone());
        c.setType(m.getType());
        c.setTemplateName(m.getTemplateName());
        c.setStatus(m.getStatus());
        c.setErrorCode(m.getErrorCode());
        c.setRelatedType(m.getRelatedType());
        c.setRelatedId(m.getRelatedId());
        c.setSentBy(m.getSentBy());
        c.setCampaignId(m.getCampaignId());
        c.setDeliveredAt(m.getDeliveredAt());
        c.setReadAt(m.getReadAt());
        c.setCreatedAt(m.getCreatedAt());
        c.setUpdatedAt(m.getUpdatedAt());
        c.setBody(null);
        c.setTemplateParams(null);
        c.setMediaUrl(null);
        c.setErrorMessage(null);
        c.setBodyHidden(true);
        return c;
    }

    private static Map<String, Object> accountView(WhatsAppAccount a, boolean admin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("organizationId", a.getOrganizationId());
        m.put("displayPhoneNumber", a.getDisplayPhoneNumber());
        m.put("active", a.isActive());
        if (admin) {
            m.put("phoneNumberId", a.getPhoneNumberId());
            m.put("businessAccountId", a.getBusinessAccountId());
            m.put("hasAccessToken", a.hasAccessToken());
            m.put("accessTokenHint", a.accessTokenHint());
            m.put("webhookVerifyTokenSet", a.webhookVerifyTokenSet());
            m.put("createdBy", a.getCreatedBy());
            m.put("createdAt", a.getCreatedAt());
            m.put("updatedAt", a.getUpdatedAt());
        }
        return m;
    }

    private void requirePhoneNumberIdFree(String phoneNumberId, Long selfId) {
        accountRepo.findByPhoneNumberId(phoneNumberId).ifPresent(existing -> {
            if (!existing.getId().equals(selfId))
                throw new IllegalStateException("This WhatsApp phone number ID is already connected");
        });
    }

    private WhatsAppAccount saveAccount(WhatsAppAccount acct) {
        try {
            return accountRepo.saveAndFlush(acct);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("This WhatsApp phone number ID is already connected");
        }
    }

    /** Validates that the linked record exists in the actor's org and is visible to them. */
    private void requireRecordAccess(User actor, String relatedType, Long relatedId) {
        Long orgId = actor.getOrganizationId();
        switch (relatedType) {
            case "Lead" -> {
                Lead l = leadRepo.findByIdAndOrganizationId(relatedId, orgId)
                        .orElseThrow(() -> new NoSuchElementException("Lead not found"));
                permissionService.require(actor, Action.VIEW, ResourceType.LEAD, l.getOrganizationId(), l.getOwnerId());
            }
            case "Contact" -> {
                Contact c = contactRepo.findByIdAndOrganizationId(relatedId, orgId)
                        .orElseThrow(() -> new NoSuchElementException("Contact not found"));
                permissionService.require(actor, Action.VIEW, ResourceType.CONTACT, c.getOrganizationId(), c.getOwnerId());
            }
            case "Opportunity" -> {
                Opportunity o = opportunityRepo.findByIdAndOrganizationId(relatedId, orgId)
                        .orElseThrow(() -> new NoSuchElementException("Opportunity not found"));
                permissionService.require(actor, Action.VIEW, ResourceType.OPPORTUNITY, o.getOrganizationId(), o.getOwnerId());
            }
            case "Campaign" -> permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, orgId, null);
            default -> throw new IllegalArgumentException("relatedType must be Lead, Contact, Opportunity or Campaign");
        }
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(200, limit));
    }

    private static String asString(Object v, String field) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw new IllegalArgumentException(field + " must be a string");
    }

    private static Long asLong(Object v, String field) {
        if (v == null) return null;
        if (v instanceof Integer || v instanceof Long || v instanceof Short) return ((Number) v).longValue();
        if (v instanceof String s && s.matches("\\d{1,18}")) return Long.parseLong(s);
        throw new IllegalArgumentException(field + " must be an integer id");
    }

    private static String optString(Map<String, Object> body, String field, int maxLen) {
        String s = asString(body.get(field), field);
        if (s == null) return null;
        s = s.trim();
        if (s.length() > maxLen) throw new IllegalArgumentException(field + " exceeds " + maxLen + " characters");
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asComponents(Object v) {
        if (v == null) return null;
        if (!(v instanceof List<?> list)) throw new IllegalArgumentException("components must be an array");
        for (Object o : list) {
            if (!(o instanceof Map)) throw new IllegalArgumentException("components must be an array of objects");
        }
        return (List<Map<String, Object>>) v;
    }
}
