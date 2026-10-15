package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AiAutomationService;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.EmailIntegrationService;
import jakarta.mail.internet.InternetAddress;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * AI Automation definitions, runs and SMTP/IMAP accounts.
 * Permissions: ResourceType.AI_AUTOMATION (VIEW / CREATE / EDIT — also run and
 * secret rotation — / DELETE: Manager+) and ResourceType.EMAIL_ACCOUNT
 * (VP / Super Admin). custom_api / send_email / read_email automations are
 * additionally limited to roles that may manage email accounts, and their config
 * and run output are hidden from everyone else.
 *
 * GET    /api/ai-automations                      — list (?limit=1..500)
 * POST   /api/ai-automations                      — create
 * GET    /api/ai-automations/{id}                 — get
 * PUT    /api/ai-automations/{id}                 — partial update
 * DELETE /api/ai-automations/{id}                 — delete
 * POST   /api/ai-automations/{id}/run             — manual run (synchronous, no transaction)
 * GET    /api/ai-automations/{id}/runs            — run log (status, error, duration_ms; ?limit=1..200)
 * POST   /api/ai-automations/{id}/webhook-secret  — (re)generate the webhook-trigger secret, shown once
 * GET|POST|DELETE /api/ai-automations/email-accounts[/{id}]
 *
 * Triggers: manual | scheduled (triggerConfigJson {"cron": "0 9 * * MON-FRI", "timezone": "UTC"},
 * evaluated every minute) | webhook (POST /api/automations/{id}/hook, HMAC-signed — see
 * AutomationWebhookController) | on_lead_created | on_stage_change | on_campaign_approved.
 *
 * Secrets are write-only: send "apiKey" (legacy "apiKeyEncrypted") / "smtpPassword";
 * responses carry apiKeySet + apiKeyHint / smtpPasswordSet / webhookSecretSet. The
 * webhook secret itself is returned only by create / rotate, exactly once.
 */
@RestController
@RequestMapping("/api/ai-automations")
public class AiAutomationController {

    private static final int MAX_CONTEXT_KEYS = 100;
    private static final int MAX_CONTEXT_VALUE = 10_000;

    private final AiAutomationRepository automationRepo;
    private final AiAutomationRunRepository runRepo;
    private final EmailAccountRepository emailAccountRepo;
    private final AiAutomationService automationService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public AiAutomationController(AiAutomationRepository automationRepo,
                                  AiAutomationRunRepository runRepo,
                                  EmailAccountRepository emailAccountRepo,
                                  AiAutomationService automationService,
                                  PermissionService permissionService,
                                  AuditService auditService,
                                  CurrentUser currentUser) {
        this.automationRepo = automationRepo;
        this.runRepo = runRepo;
        this.emailAccountRepo = emailAccountRepo;
        this.automationService = automationService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    // ── Automation definitions ─────────────────────────────────────────────────

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "200") int limit) {
        User actor = require(Action.VIEW);
        return automationRepo.findByOrganizationIdOrderByCreatedAtDesc(actor.getOrganizationId(),
                        PageRequest.of(0, Math.max(1, Math.min(500, limit))))
                .stream().map(a -> view(a, actor)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        User actor = require(Action.VIEW);
        return automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .map(a -> ResponseEntity.ok(view(a, actor)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        User actor = require(Action.CREATE);

        AiAutomation a = new AiAutomation();
        a.setOrganizationId(actor.getOrganizationId());
        a.setName(requireName(str(body, "name")));
        a.setDescription(str(body, "description"));
        a.setTriggerType(str(body, "triggerType"));
        a.setTriggerConfigJson(blankToNull(str(body, "triggerConfigJson")));
        a.setActionType(str(body, "actionType"));
        a.setActionConfigJson(str(body, "actionConfigJson"));
        String provider = str(body, "apiProvider");
        a.setApiProvider(provider != null ? provider : "anthropic");
        String model = str(body, "modelId");
        a.setModelId(model != null && !model.isBlank() ? model
                : AiAutomationService.effectiveModel(a.getApiProvider(), null));
        String key = apiKeyFrom(body);
        if (key != null && !key.isBlank()) a.setApiKeyEncrypted(key.trim());
        a.setActive(bool(body, "active", true));

        validate(a, actor);
        String secret = null;
        if ("webhook".equals(a.getTriggerType())) {
            secret = AiAutomationService.generateWebhookSecret();
            a.setWebhookSecret(secret);
        }
        a.setCreatedBy(actor.getId());
        a.setRunCount(0);
        a.setLastRunAt(null);
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        AiAutomation saved = automationRepo.save(a);
        Map<String, Object> v = view(saved, actor);
        auditService.log("create", "ai_automations", saved.getId(), saved.getName(), null, v);
        if (secret != null) v.put("webhookSecret", secret);   // shown exactly once
        return ResponseEntity.status(201).body(v);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> body) {
        User actor = require(Action.EDIT);
        AiAutomation existing = automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        requirePrivilegedAccess(actor, existing.getActionType());
        Map<String, Object> before = view(existing, actor);
        String oldTrigger = existing.getTriggerType();
        String oldTriggerConfig = existing.getTriggerConfigJson();

        if (body.get("name") != null)              existing.setName(requireName(str(body, "name")));
        if (body.containsKey("description"))       existing.setDescription(str(body, "description"));
        if (body.get("triggerType") != null)       existing.setTriggerType(str(body, "triggerType"));
        if (body.containsKey("triggerConfigJson")) existing.setTriggerConfigJson(blankToNull(str(body, "triggerConfigJson")));
        if (body.get("actionType") != null)        existing.setActionType(str(body, "actionType"));
        if (body.get("actionConfigJson") != null)  existing.setActionConfigJson(str(body, "actionConfigJson"));
        if (body.get("apiProvider") != null)       existing.setApiProvider(str(body, "apiProvider"));
        if (body.get("modelId") != null)           existing.setModelId(str(body, "modelId"));
        String key = apiKeyFrom(body);
        if (key != null && !key.isBlank())         existing.setApiKeyEncrypted(key.trim());
        if (Boolean.TRUE.equals(body.get("clearApiKey"))) existing.setApiKeyEncrypted(null);
        // Partial update: activation only changes when explicitly provided.
        if (body.get("active") != null)            existing.setActive(bool(body, "active", existing.isActive()));

        validate(existing, actor);
        // A new schedule starts counting from now, not from the old schedule's last fire.
        if (!Objects.equals(oldTrigger, existing.getTriggerType())
                || !Objects.equals(oldTriggerConfig, existing.getTriggerConfigJson())) {
            existing.setScheduleLastFiredAt(null);
        }
        String secret = null;
        if ("webhook".equals(existing.getTriggerType()) && existing.getWebhookSecret() == null) {
            secret = AiAutomationService.generateWebhookSecret();
            existing.setWebhookSecret(secret);
        }
        existing.setUpdatedAt(Instant.now());
        AiAutomation saved = automationRepo.save(existing);
        Map<String, Object> v = view(saved, actor);
        auditService.log("update", "ai_automations", saved.getId(), saved.getName(), before, v);
        if (secret != null) v.put("webhookSecret", secret);
        return ResponseEntity.ok(v);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = require(Action.DELETE);
        AiAutomation existing = automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        requirePrivilegedAccess(actor, existing.getActionType());
        automationRepo.delete(existing);
        auditService.log("delete", "ai_automations", existing.getId(), existing.getName(), view(existing, actor), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Generates (or rotates) the webhook-trigger secret; the old one stops working immediately. */
    @PostMapping("/{id}/webhook-secret")
    public ResponseEntity<Map<String, Object>> rotateWebhookSecret(@PathVariable Long id) {
        User actor = require(Action.EDIT);
        AiAutomation a = automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        requirePrivilegedAccess(actor, a.getActionType());
        if (!"webhook".equals(a.getTriggerType()))
            throw new IllegalStateException("Only webhook-triggered automations have a webhook secret");
        String secret = AiAutomationService.generateWebhookSecret();
        a.setWebhookSecret(secret);
        a.setUpdatedAt(Instant.now());
        automationRepo.save(a);
        auditService.log("rotate_webhook_secret", "ai_automations", a.getId(), a.getName(), null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("webhookSecret", secret);
        out.put("hookPath", hookPath(a));
        out.put("signature", "X-Signature: sha256=hex(HMAC_SHA256(secret, X-Timestamp + \".\" + body)); "
                + "X-Timestamp: unix seconds, within 5 minutes");
        return ResponseEntity.ok(out);
    }

    // ── Manual execution ───────────────────────────────────────────────────────

    @PostMapping("/{id}/run")
    public ResponseEntity<AiAutomationRun> run(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        User actor = require(Action.EDIT);
        AiAutomation a = automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        requirePrivilegedAccess(actor, a.getActionType());
        Map<String, String> context = new HashMap<>();
        Object raw = body != null ? body.get("context") : null;
        if (raw != null) {
            if (!(raw instanceof Map<?, ?> m)) throw new IllegalArgumentException("context must be an object");
            if (m.size() > MAX_CONTEXT_KEYS) throw new IllegalArgumentException("context has too many keys");
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (!(v instanceof String || v instanceof Number || v instanceof Boolean))
                    throw new IllegalArgumentException("context values must be strings, numbers or booleans");
                String s = String.valueOf(v);
                if (s.length() > MAX_CONTEXT_VALUE) throw new IllegalArgumentException("context value too long");
                context.put(String.valueOf(e.getKey()), s);
            }
        }
        AiAutomationRun run = automationService.executeManual(id, actor.getOrganizationId(), actor.getId(), context);
        return ResponseEntity.ok(run);
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<AiAutomationRun>> getRuns(@PathVariable Long id,
                                                         @RequestParam(defaultValue = "50") int limit) {
        User actor = require(Action.VIEW);
        AiAutomation a = automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        // Run output of email/API automations can contain mailbox contents or third-party data.
        requirePrivilegedAccess(actor, a.getActionType());
        return ResponseEntity.ok(runRepo.findByAutomationIdAndOrganizationIdOrderByStartedAtDesc(
                id, actor.getOrganizationId(), PageRequest.of(0, Math.max(1, Math.min(200, limit)))));
    }

    // ── Email accounts ─────────────────────────────────────────────────────────

    @GetMapping("/email-accounts")
    public List<EmailAccount> listEmailAccounts() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.EMAIL_ACCOUNT, actor.getOrganizationId(), null);
        return emailAccountRepo.findByOrganizationIdAndIsActiveTrue(actor.getOrganizationId());
    }

    @PostMapping("/email-accounts")
    public ResponseEntity<EmailAccount> addEmailAccount(@RequestBody EmailAccount body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.EMAIL_ACCOUNT, actor.getOrganizationId(), null);

        // Built from allow-listed, validated fields. The password is encrypted at rest
        // (EncryptedStringConverter) and never serialized back (smtpPasswordSet instead).
        EmailAccount a = new EmailAccount();
        a.setOrganizationId(actor.getOrganizationId());
        a.setUserId(actor.getId());
        a.setEmailAddress(requireEmail(body.getEmailAddress()));
        a.setDisplayName(trimTo(body.getDisplayName(), 100));
        a.setProvider("smtp_imap");
        a.setSmtpHost(EmailIntegrationService.requireSafeHost(body.getSmtpHost(), "smtpHost"));
        a.setSmtpPort(EmailIntegrationService.requireSmtpPort(body.getSmtpPort()));
        a.setSmtpUser(trimTo(body.getSmtpUser(), 200));
        if (body.getSmtpPasswordEncrypted() == null || body.getSmtpPasswordEncrypted().isBlank())
            throw new IllegalArgumentException("smtpPassword is required");
        a.setSmtpPasswordEncrypted(body.getSmtpPasswordEncrypted());
        a.setSmtpTls(body.isSmtpTls());
        if (body.getImapHost() != null && !body.getImapHost().isBlank()) {
            a.setImapHost(EmailIntegrationService.requireSafeHost(body.getImapHost(), "imapHost"));
            a.setImapPort(EmailIntegrationService.requireImapPort(body.getImapPort()));
        }
        a.setImapSsl(body.isImapSsl());
        a.setActive(true);
        a.setCreatedAt(Instant.now());
        EmailAccount saved;
        try {
            saved = emailAccountRepo.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("This email address is already connected");
        }
        auditService.log("create", "email_accounts", saved.getId(), saved.getEmailAddress(), null,
                Map.of("emailAddress", saved.getEmailAddress(), "smtpHost", saved.getSmtpHost()));
        return ResponseEntity.status(201).body(saved);
    }

    @DeleteMapping("/email-accounts/{accountId}")
    public ResponseEntity<?> deleteEmailAccount(@PathVariable Long accountId) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.EMAIL_ACCOUNT, actor.getOrganizationId(), null);
        EmailAccount existing = emailAccountRepo.findByIdAndOrganizationId(
                accountId, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        emailAccountRepo.delete(existing);
        auditService.log("delete", "email_accounts", existing.getId(), existing.getEmailAddress(),
                Map.of("emailAddress", existing.getEmailAddress()), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private User require(Action action) {
        User actor = currentUser.get();
        permissionService.require(actor, action, ResourceType.AI_AUTOMATION, actor.getOrganizationId(), null);
        return actor;
    }

    private void validate(AiAutomation a, User actor) {
        requirePrivilegedAccess(actor, a.getActionType());
        automationService.validateDefinition(a.getTriggerType(), a.getActionType(), a.getActionConfigJson(),
                a.getApiProvider(), a.getModelId());
        automationService.validateTrigger(a.getTriggerType(), a.getTriggerConfigJson());
        automationService.requireEmailAccountInOrg(a.getActionConfigJson(), actor.getOrganizationId());
    }

    /** Email / custom-API automations: only roles that may manage email accounts (VP / Super Admin). */
    private boolean privileged(User actor) {
        return permissionService.can(actor, Action.CREATE, ResourceType.EMAIL_ACCOUNT, actor.getOrganizationId(), null);
    }

    private void requirePrivilegedAccess(User actor, String actionType) {
        if (actionType != null && AiAutomationService.PRIVILEGED_ACTION_TYPES.contains(actionType) && !privileged(actor)) {
            throw new AccessDeniedException("Only SUPER_ADMIN or VP_SALES can manage " + actionType + " automations");
        }
    }

    private static String hookPath(AiAutomation a) {
        return "/api/automations/" + a.getId() + "/hook";
    }

    /** Response shape — never includes the API key or webhook secret; hides privileged configs from non-admins. */
    private Map<String, Object> view(AiAutomation a, User actor) {
        boolean redact = AiAutomationService.PRIVILEGED_ACTION_TYPES.contains(a.getActionType()) && !privileged(actor);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("organizationId", a.getOrganizationId());
        m.put("name", a.getName());
        m.put("description", a.getDescription());
        m.put("triggerType", a.getTriggerType());
        m.put("triggerConfigJson", redact ? null : a.getTriggerConfigJson());
        m.put("actionType", a.getActionType());
        m.put("actionConfigJson", redact ? null : a.getActionConfigJson());
        m.put("apiProvider", a.getApiProvider());
        m.put("apiKeySet", a.apiKeySet());
        m.put("apiKeyHint", redact ? null : a.apiKeyHint());
        m.put("modelId", a.getModelId());
        m.put("active", a.isActive());
        m.put("runCount", a.getRunCount());
        m.put("lastRunAt", a.getLastRunAt());
        boolean webhook = "webhook".equals(a.getTriggerType());
        m.put("webhookSecretSet", webhook && a.getWebhookSecret() != null && !a.getWebhookSecret().isBlank());
        m.put("hookPath", webhook ? hookPath(a) : null);
        Instant nextRun = null;
        if ("scheduled".equals(a.getTriggerType()) && a.isActive()) {
            try {
                Instant base = a.getScheduleLastFiredAt() != null ? a.getScheduleLastFiredAt()
                        : (a.getUpdatedAt() != null ? a.getUpdatedAt() : Instant.now());
                nextRun = AiAutomationService.nextFire(automationService.schedule(a.getTriggerConfigJson()), base);
            } catch (IllegalArgumentException ignored) { }
        }
        m.put("nextRunAt", nextRun);
        m.put("createdBy", a.getCreatedBy());
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        return m;
    }

    private static String apiKeyFrom(Map<String, Object> body) {
        Object k = body.get("apiKey") != null ? body.get("apiKey") : body.get("apiKeyEncrypted");
        if (k == null) return null;
        if (!(k instanceof String s)) throw new IllegalArgumentException("apiKey must be a string");
        if (s.length() > 500) throw new IllegalArgumentException("apiKey is too long");
        return s;
    }

    private static String str(Map<String, Object> body, String field) {
        Object v = body.get(field);
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw new IllegalArgumentException(field + " must be a string");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean bool(Map<String, Object> body, String field, boolean def) {
        Object v = body.get(field);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        throw new IllegalArgumentException(field + " must be a boolean");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        String n = name.trim();
        if (n.length() > 200) throw new IllegalArgumentException("name exceeds 200 characters");
        return n;
    }

    private static String requireEmail(String email) {
        InternetAddress a = EmailIntegrationService.requireSingleAddress(email);
        if (a.getAddress().length() > 200) throw new IllegalArgumentException("emailAddress is too long");
        return a.getAddress();
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.length() > max) throw new IllegalArgumentException("value exceeds " + max + " characters");
        return t;
    }
}
