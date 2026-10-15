package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AiAutomationService;
import com.betteranalytics.crm.service.EmailIntegrationService;
import jakarta.mail.internet.InternetAddress;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * AI Automation definitions and manual execution.
 *
 * GET    /api/ai-automations                    — list
 * POST   /api/ai-automations                    — create             (MANAGER+)
 * GET    /api/ai-automations/{id}               — get
 * PUT    /api/ai-automations/{id}               — partial update     (MANAGER+)
 * DELETE /api/ai-automations/{id}               — delete             (MANAGER+)
 * POST   /api/ai-automations/{id}/run           — manually trigger   (MANAGER+)
 * GET    /api/ai-automations/{id}/runs          — run history (?limit=1..200)
 *
 * custom_api / send_email / read_email automations can only be created,
 * modified or deleted by SUPER_ADMIN / VP_SALES, and their config + run output
 * is only shown to those roles.
 *
 * Email accounts (SUPER_ADMIN / VP_SALES only):
 * GET    /api/ai-automations/email-accounts     — list email accounts
 * POST   /api/ai-automations/email-accounts     — add email account
 * DELETE /api/ai-automations/email-accounts/{id}
 *
 * Secrets are write-only: send "apiKey" (or legacy "apiKeyEncrypted") / "smtpPassword";
 * responses carry apiKeySet + apiKeyHint / smtpPasswordSet.
 *
 * Template variable reference:
 *   Use {{variable_name}} in prompts and config JSON.
 *   Pass {"context": {"name": "value"}} in the run body.
 */
@RestController
@RequestMapping("/api/ai-automations")
public class AiAutomationController {

    private static final Set<Role> MANAGER_PLUS = EnumSet.of(Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER);
    private static final Set<Role> ADMINS = EnumSet.of(Role.SUPER_ADMIN, Role.VP_SALES);
    private static final int MAX_CONTEXT_KEYS = 100;
    private static final int MAX_CONTEXT_VALUE = 10_000;

    private final AiAutomationRepository automationRepo;
    private final AiAutomationRunRepository runRepo;
    private final EmailAccountRepository emailAccountRepo;
    private final AiAutomationService automationService;
    private final CurrentUser currentUser;

    public AiAutomationController(AiAutomationRepository automationRepo,
                                   AiAutomationRunRepository runRepo,
                                   EmailAccountRepository emailAccountRepo,
                                   AiAutomationService automationService,
                                   CurrentUser currentUser) {
        this.automationRepo = automationRepo;
        this.runRepo = runRepo;
        this.emailAccountRepo = emailAccountRepo;
        this.automationService = automationService;
        this.currentUser = currentUser;
    }

    // ── Automation definitions ─────────────────────────────────────────────────

    @GetMapping
    public List<Map<String, Object>> list() {
        User actor = currentUser.get();
        return automationRepo.findByOrganizationIdOrderByCreatedAtDesc(actor.getOrganizationId())
                .stream().map(a -> view(a, actor)).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        User actor = currentUser.get();
        return automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .map(a -> ResponseEntity.ok(view(a, actor)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        requireRole(actor, MANAGER_PLUS, "MANAGER or above required to create automations");

        AiAutomation a = new AiAutomation();
        a.setOrganizationId(actor.getOrganizationId());
        a.setName(requireName(str(body, "name")));
        a.setDescription(str(body, "description"));
        a.setTriggerType(str(body, "triggerType"));
        a.setTriggerConfigJson(str(body, "triggerConfigJson"));
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
        a.setCreatedBy(actor.getId());
        a.setRunCount(0);
        a.setLastRunAt(null);
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return ResponseEntity.status(201).body(view(automationRepo.save(a), actor));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                       @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        requireRole(actor, MANAGER_PLUS, "MANAGER or above required to edit automations");
        AiAutomation existing = automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        requirePrivilegedAccess(actor, existing.getActionType());

        if (body.get("name") != null)              existing.setName(requireName(str(body, "name")));
        if (body.containsKey("description"))       existing.setDescription(str(body, "description"));
        if (body.get("triggerType") != null)       existing.setTriggerType(str(body, "triggerType"));
        if (body.containsKey("triggerConfigJson")) existing.setTriggerConfigJson(str(body, "triggerConfigJson"));
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
        existing.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(view(automationRepo.save(existing), actor));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        requireRole(actor, MANAGER_PLUS, "MANAGER or above required to delete automations");
        AiAutomation existing = automationRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        requirePrivilegedAccess(actor, existing.getActionType());
        automationRepo.delete(existing);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Manual execution ───────────────────────────────────────────────────────

    @PostMapping("/{id}/run")
    public ResponseEntity<AiAutomationRun> run(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        requireRole(actor, MANAGER_PLUS, "MANAGER or above required to run automations");
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
        AiAutomationRun run = automationService.executeManual(id, actor.getOrganizationId(),
                actor.getId(), context);
        return ResponseEntity.ok(run);
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<AiAutomationRun>> getRuns(@PathVariable Long id,
                                                         @RequestParam(defaultValue = "50") int limit) {
        User actor = currentUser.get();
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
        requireRole(actor, ADMINS, "Admin or VP_SALES required");
        return emailAccountRepo.findByOrganizationIdAndIsActiveTrue(actor.getOrganizationId());
    }

    @PostMapping("/email-accounts")
    public ResponseEntity<EmailAccount> addEmailAccount(@RequestBody EmailAccount body) {
        User actor = currentUser.get();
        requireRole(actor, ADMINS, "Admin or VP_SALES required");

        // Build from allow-listed, validated fields.
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
        return ResponseEntity.status(201).body(emailAccountRepo.save(a));
    }

    @DeleteMapping("/email-accounts/{accountId}")
    public ResponseEntity<?> deleteEmailAccount(@PathVariable Long accountId) {
        User actor = currentUser.get();
        requireRole(actor, ADMINS, "Admin or VP_SALES required");
        EmailAccount existing = emailAccountRepo.findByIdAndOrganizationId(
                accountId, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        emailAccountRepo.delete(existing);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private void validate(AiAutomation a, User actor) {
        requirePrivilegedAccess(actor, a.getActionType());
        automationService.validateDefinition(a.getTriggerType(), a.getActionType(), a.getActionConfigJson(),
                a.getApiProvider(), a.getModelId());
        automationService.requireEmailAccountInOrg(a.getActionConfigJson(), actor.getOrganizationId());
    }

    private static void requireRole(User actor, Set<Role> roles, String message) {
        if (!roles.contains(actor.getRole())) throw new AccessDeniedException(message);
    }

    private static void requirePrivilegedAccess(User actor, String actionType) {
        if (actionType != null && AiAutomationService.PRIVILEGED_ACTION_TYPES.contains(actionType)
                && !ADMINS.contains(actor.getRole())) {
            throw new AccessDeniedException("Only SUPER_ADMIN or VP_SALES can manage " + actionType + " automations");
        }
    }

    /** Response shape — never includes the API key; hides privileged configs from non-admins. */
    private static Map<String, Object> view(AiAutomation a, User actor) {
        boolean redact = AiAutomationService.PRIVILEGED_ACTION_TYPES.contains(a.getActionType())
                && !ADMINS.contains(actor.getRole());
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
