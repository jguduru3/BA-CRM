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
import com.betteranalytics.crm.service.WhatsAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * WhatsApp Cloud API integration endpoints.
 *
 * GET    /api/whatsapp/webhook               — Meta webhook verification (hub.challenge)
 * POST   /api/whatsapp/webhook               — inbound messages + status updates (no JWT; X-Hub-Signature-256 verified)
 * POST   /api/whatsapp/send                  — send text or template message
 * GET    /api/whatsapp/conversations/phone/{phone} — message thread by phone number
 * GET    /api/whatsapp/conversations/{type}/{id}   — message thread for a CRM record
 *
 * Account management (SUPER_ADMIN / VP_SALES only):
 * GET    /api/whatsapp/accounts
 * POST   /api/whatsapp/accounts
 * PUT    /api/whatsapp/accounts/{id}
 * DELETE /api/whatsapp/accounts/{id}
 *
 * accessToken / webhookVerifyToken are write-only: responses carry
 * hasAccessToken, accessTokenHint and webhookVerifyTokenSet instead.
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppController.class);
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9_.-]{1,128}$");
    private static final int MAX_TEXT = 4096;

    private final WhatsAppService           whatsAppService;
    private final WhatsAppAccountRepository accountRepo;
    private final WhatsAppMessageRepository messageRepo;
    private final LeadRepository            leadRepo;
    private final ContactRepository         contactRepo;
    private final OpportunityRepository     opportunityRepo;
    private final PermissionService         permissionService;
    private final CurrentUser               currentUser;

    public WhatsAppController(WhatsAppService whatsAppService,
                               WhatsAppAccountRepository accountRepo,
                               WhatsAppMessageRepository messageRepo,
                               LeadRepository leadRepo,
                               ContactRepository contactRepo,
                               OpportunityRepository opportunityRepo,
                               PermissionService permissionService,
                               CurrentUser currentUser) {
        this.whatsAppService = whatsAppService;
        this.accountRepo = accountRepo;
        this.messageRepo = messageRepo;
        this.leadRepo = leadRepo;
        this.contactRepo = contactRepo;
        this.opportunityRepo = opportunityRepo;
        this.permissionService = permissionService;
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
        Long accountId = asLong(body.get("accountId"), "accountId");
        String phone   = WhatsAppService.normalizePhone(asString(body.get("phone"), "phone"));
        String type    = body.get("type") == null ? "text" : asString(body.get("type"), "type");
        String relType = body.get("relatedType") == null ? null : asString(body.get("relatedType"), "relatedType");
        Long relId     = body.get("relatedId") == null ? null : asLong(body.get("relatedId"), "relatedId");

        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        if ((relType == null) != (relId == null))
            throw new IllegalArgumentException("relatedType and relatedId must be given together");

        if (relType != null) {
            // Messaging a CRM record requires being able to see that record.
            requireRecordAccess(actor, relType, relId);
        } else {
            // Free-form sends to arbitrary numbers are a marketing action.
            permissionService.require(actor, Action.CREATE, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        }

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

    // ── Conversation threads ──────────────────────────────────────────────────

    @GetMapping("/conversations/{relatedType}/{relatedId}")
    public ResponseEntity<List<WhatsAppMessage>> conversationByRecord(
            @PathVariable String relatedType, @PathVariable Long relatedId,
            @RequestParam(defaultValue = "200") int limit) {
        User actor = currentUser.get();
        requireRecordAccess(actor, relatedType, relatedId);
        return ResponseEntity.ok(
                messageRepo.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
                        actor.getOrganizationId(), relatedType, relatedId, PageRequest.of(0, clamp(limit))));
    }

    @GetMapping("/conversations/phone/{phone}")
    public ResponseEntity<List<WhatsAppMessage>> conversationByPhone(
            @PathVariable String phone, @RequestParam(defaultValue = "50") int limit) {
        User actor = currentUser.get();
        return ResponseEntity.ok(
                messageRepo.findByOrganizationIdAndPhoneOrderByCreatedAtDesc(
                        actor.getOrganizationId(), phone, PageRequest.of(0, clamp(limit))));
    }

    // ── Account management ────────────────────────────────────────────────────

    @GetMapping("/accounts")
    public ResponseEntity<List<WhatsAppAccount>> listAccounts() {
        User actor = currentUser.get();
        requireAdminOrVP(actor);
        return ResponseEntity.ok(accountRepo.findByOrganizationId(actor.getOrganizationId()));
    }

    @PostMapping("/accounts")
    public ResponseEntity<WhatsAppAccount> createAccount(@RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        requireAdminOrVP(actor);

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
        return ResponseEntity.ok(saveAccount(acct));
    }

    @PutMapping("/accounts/{id}")
    public ResponseEntity<WhatsAppAccount> updateAccount(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        requireAdminOrVP(actor);
        WhatsAppAccount acct = accountRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (acct == null) return ResponseEntity.notFound().build();
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
        return ResponseEntity.ok(saveAccount(acct));
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id) {
        User actor = currentUser.get();
        requireAdminOrVP(actor);
        WhatsAppAccount acct = accountRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (acct == null) return ResponseEntity.notFound().build();
        accountRepo.delete(acct);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireAdminOrVP(User actor) {
        Role role = actor.getRole();
        if (role != Role.SUPER_ADMIN && role != Role.VP_SALES) {
            throw new AccessDeniedException("Admin or VP_SALES required");
        }
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
