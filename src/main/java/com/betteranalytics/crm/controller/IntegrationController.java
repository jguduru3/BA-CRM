package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ApiKeyRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.repository.WebhookDeliveryRepository;
import com.betteranalytics.crm.repository.WebhookSubscriptionRepository;
import com.betteranalytics.crm.security.ApiKeyAuthFilter;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.security.UrlSafety;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.WebhookService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Admin management of the Master API integration layer: API keys and outbound
 * webhooks. Super Admin only. Deliberately NOT reachable through the Master API
 * itself (the gateway blocks /integrations), so a leaked key can never mint
 * more keys or redirect webhooks.
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {

    private static final int MAX_KEYS_PER_ORG = 50;
    private static final int MAX_WEBHOOKS_PER_ORG = 25;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final WebhookSubscriptionRepository webhookRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final WebhookService webhookService;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;

    public IntegrationController(ApiKeyRepository apiKeyRepository, WebhookSubscriptionRepository webhookRepository,
                                 WebhookDeliveryRepository deliveryRepository, UserRepository userRepository,
                                 WebhookService webhookService, AuditService auditService, CurrentUser currentUser,
                                 PermissionService permissionService) {
        this.apiKeyRepository = apiKeyRepository;
        this.webhookRepository = webhookRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.webhookService = webhookService;
        this.auditService = auditService;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
    }

    /** Overview used by the Integrations screen. */
    @GetMapping
    public Map<String, Object> overview() {
        requireAdmin();
        Long org = currentUser.organizationId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiKeys", apiKeyRepository.findByOrganizationIdOrderByCreatedAtDesc(org).stream().map(this::keyView).toList());
        out.put("webhooks", webhookRepository.findByOrganizationIdOrderByCreatedAtDesc(org).stream().map(this::hookView).toList());
        out.put("events", WebhookService.EVENTS);
        out.put("masterApiBase", "/api/master/v1");
        return out;
    }

    // ── API keys ───────────────────────────────────────────────────────────

    @GetMapping("/api-keys")
    public List<Map<String, Object>> listKeys() {
        requireAdmin();
        return apiKeyRepository.findByOrganizationIdOrderByCreatedAtDesc(currentUser.organizationId())
                .stream().map(this::keyView).toList();
    }

    /**
     * Body: {name, scope: "read"|"read_write", actingUserId?, expiresInDays?}.
     * The response carries the plaintext key — the only time it is ever shown.
     */
    @PostMapping("/api-keys")
    public ResponseEntity<?> createKey(@RequestBody Map<String, Object> body) {
        User actor = requireAdmin();
        Long org = actor.getOrganizationId();
        String name = str(body.get("name"));
        if (name == null || name.isBlank() || name.length() > 150) {
            throw new IllegalArgumentException("name is required (max 150 characters)");
        }
        String scope = Optional.ofNullable(str(body.get("scope"))).orElse(ApiKey.SCOPE_READ);
        if (!scope.equals(ApiKey.SCOPE_READ) && !scope.equals(ApiKey.SCOPE_READ_WRITE)) {
            throw new IllegalArgumentException("scope must be 'read' or 'read_write'");
        }
        Long actingUserId = body.get("actingUserId") == null ? actor.getId() : longVal(body.get("actingUserId"), "actingUserId");
        User acting = userRepository.findByIdAndOrganizationId(actingUserId, org)
                .orElseThrow(() -> new IllegalArgumentException("actingUserId is not a user in your organization"));
        if (!acting.isActive()) throw new IllegalArgumentException("acting user is deactivated");

        Instant expiresAt = null;
        if (body.get("expiresInDays") != null) {
            long days = longVal(body.get("expiresInDays"), "expiresInDays");
            if (days < 1 || days > 3650) throw new IllegalArgumentException("expiresInDays must be between 1 and 3650");
            expiresAt = Instant.now().plus(days, ChronoUnit.DAYS);
        }
        long active = apiKeyRepository.findByOrganizationIdOrderByCreatedAtDesc(org).stream()
                .filter(k -> k.usableAt(Instant.now())).count();
        if (active >= MAX_KEYS_PER_ORG) throw new IllegalStateException("too many active API keys; revoke unused ones first");

        String raw = ApiKeyAuthFilter.generateKey();
        ApiKey key = new ApiKey();
        key.setOrganizationId(org);
        key.setName(name.trim());
        key.setKeyPrefix(raw.substring(0, 12));
        key.setKeyHash(ApiKeyAuthFilter.hash(raw));
        key.setScope(scope);
        key.setActingUserId(acting.getId());
        key.setCreatedBy(actor.getId());
        key.setCreatedAt(Instant.now());
        key.setExpiresAt(expiresAt);
        apiKeyRepository.save(key);
        auditService.log("create", "api_keys", key.getId(), key.getName(), null,
                Map.of("scope", scope, "actingUserId", acting.getId(), "prefix", key.getKeyPrefix()));

        Map<String, Object> out = keyView(key);
        out.put("key", raw);
        return ResponseEntity.status(201).body(out);
    }

    @DeleteMapping("/api-keys/{id}")
    public ResponseEntity<?> revokeKey(@PathVariable Long id) {
        requireAdmin();
        ApiKey key = apiKeyRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("API key not found"));
        if (key.getRevokedAt() == null) {
            key.setRevokedAt(Instant.now());
            apiKeyRepository.save(key);
            auditService.log("delete", "api_keys", key.getId(), key.getName(), Map.of("prefix", key.getKeyPrefix()), null);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Webhooks ───────────────────────────────────────────────────────────

    @GetMapping("/webhooks")
    public List<Map<String, Object>> listHooks() {
        requireAdmin();
        return webhookRepository.findByOrganizationIdOrderByCreatedAtDesc(currentUser.organizationId())
                .stream().map(this::hookView).toList();
    }

    /** Body: {name, url, events: ["lead.created", "lead.*", "*"]}. Response includes the signing secret once. */
    @PostMapping("/webhooks")
    public ResponseEntity<?> createHook(@RequestBody Map<String, Object> body) {
        User actor = requireAdmin();
        Long org = actor.getOrganizationId();
        if (webhookRepository.findByOrganizationIdOrderByCreatedAtDesc(org).size() >= MAX_WEBHOOKS_PER_ORG) {
            throw new IllegalStateException("webhook limit reached for this organization");
        }
        WebhookSubscription s = new WebhookSubscription();
        s.setOrganizationId(org);
        applyHook(s, body, true);
        String secret = newSecret();
        s.setSecret(secret);
        s.setCreatedBy(actor.getId());
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        webhookRepository.save(s);
        auditService.log("create", "webhooks", s.getId(), s.getName(), null, Map.of("url", s.getUrl(), "events", s.getEvents()));
        Map<String, Object> out = hookView(s);
        out.put("secret", secret);
        return ResponseEntity.status(201).body(out);
    }

    @PutMapping("/webhooks/{id}")
    public Map<String, Object> updateHook(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        WebhookSubscription s = findHook(id);
        Map<String, Object> before = hookView(s);
        applyHook(s, body, false);
        if (body.containsKey("active")) {
            boolean active = Boolean.TRUE.equals(body.get("active"));
            if (active && !s.isActive()) s.setConsecutiveFailures(0);
            s.setActive(active);
        }
        s.setUpdatedAt(Instant.now());
        webhookRepository.save(s);
        auditService.log("update", "webhooks", s.getId(), s.getName(), before, hookView(s));
        return hookView(s);
    }

    @PostMapping("/webhooks/{id}/rotate-secret")
    public Map<String, Object> rotateSecret(@PathVariable Long id) {
        requireAdmin();
        WebhookSubscription s = findHook(id);
        String secret = newSecret();
        s.setSecret(secret);
        s.setUpdatedAt(Instant.now());
        webhookRepository.save(s);
        auditService.log("update", "webhooks", s.getId(), s.getName(), null, Map.of("secretRotated", true));
        Map<String, Object> out = hookView(s);
        out.put("secret", secret);
        return out;
    }

    @PostMapping("/webhooks/{id}/test")
    public Map<String, Object> testHook(@PathVariable Long id) {
        requireAdmin();
        WebhookSubscription s = findHook(id);
        WebhookDelivery d = webhookService.sendTest(s);
        return d == null ? Map.of("success", false) : deliveryView(d);
    }

    @GetMapping("/webhooks/{id}/deliveries")
    public List<Map<String, Object>> deliveries(@PathVariable Long id, @RequestParam(defaultValue = "50") int limit) {
        requireAdmin();
        WebhookSubscription s = findHook(id);
        int size = Math.max(1, Math.min(limit, 200));
        return deliveryRepository.findBySubscriptionIdAndOrganizationIdOrderByCreatedAtDesc(
                s.getId(), s.getOrganizationId(), PageRequest.of(0, size)).stream().map(this::deliveryView).toList();
    }

    @DeleteMapping("/webhooks/{id}")
    public ResponseEntity<?> deleteHook(@PathVariable Long id) {
        requireAdmin();
        WebhookSubscription s = findHook(id);
        webhookRepository.delete(s);
        auditService.log("delete", "webhooks", s.getId(), s.getName(), Map.of("url", s.getUrl()), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void applyHook(WebhookSubscription s, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("name")) {
            String name = str(body.get("name"));
            if (name == null || name.isBlank() || name.length() > 150) {
                throw new IllegalArgumentException("name is required (max 150 characters)");
            }
            s.setName(name.trim());
        }
        if (creating || body.containsKey("url")) {
            String url = str(body.get("url"));
            if (url == null || url.length() > 1000) throw new IllegalArgumentException("url is required (max 1000 characters)");
            UrlSafety.requireSafe(url);
            s.setUrl(url.trim());
        }
        if (creating || body.containsKey("events")) {
            Object ev = body.get("events");
            List<String> events = new ArrayList<>();
            if (ev instanceof Collection<?> c) {
                for (Object o : c) if (o != null) events.add(o.toString().trim());
            } else if (ev instanceof String str) {
                for (String p : str.split(",")) if (!p.isBlank()) events.add(p.trim());
            }
            if (events.isEmpty()) throw new IllegalArgumentException("choose at least one event (or \"*\")");
            for (String e : events) {
                boolean ok = e.equals("*") || WebhookService.EVENTS.contains(e)
                        || (e.endsWith(".*") && WebhookService.EVENTS.stream().anyMatch(x -> x.startsWith(e.substring(0, e.length() - 1))));
                if (!ok) throw new IllegalArgumentException("unknown event: " + e);
            }
            s.setEvents(String.join(",", new LinkedHashSet<>(events)));
        }
    }

    private WebhookSubscription findHook(Long id) {
        return webhookRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("webhook not found"));
    }

    /** INTEGRATION rules (FeaturePermissionTable): Super Admin only, for every action. */
    private User requireAdmin() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.INTEGRATION, actor.getOrganizationId(), null);
        return actor;
    }

    private Map<String, Object> keyView(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("prefix", k.getKeyPrefix());
        m.put("scope", k.getScope());
        m.put("actingUserId", k.getActingUserId());
        m.put("actingUserName", userRepository.findById(k.getActingUserId()).map(User::getName).orElse(null));
        m.put("createdAt", k.getCreatedAt());
        m.put("expiresAt", k.getExpiresAt());
        m.put("lastUsedAt", k.getLastUsedAt());
        m.put("lastUsedIp", k.getLastUsedIp());
        m.put("requestCount", k.getRequestCount());
        m.put("revokedAt", k.getRevokedAt());
        m.put("status", k.getRevokedAt() != null ? "revoked" : k.usableAt(Instant.now()) ? "active" : "expired");
        return m;
    }

    private Map<String, Object> hookView(WebhookSubscription s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("url", s.getUrl());
        m.put("events", s.getEvents() == null ? List.of() : Arrays.asList(s.getEvents().split(",")));
        m.put("active", s.isActive());
        m.put("consecutiveFailures", s.getConsecutiveFailures());
        m.put("lastDeliveryAt", s.getLastDeliveryAt());
        m.put("lastStatusCode", s.getLastStatusCode());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    private Map<String, Object> deliveryView(WebhookDelivery d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("event", d.getEvent());
        m.put("deliveryId", d.getDeliveryUid());
        m.put("attempt", d.getAttempt());
        m.put("statusCode", d.getStatusCode());
        m.put("success", d.isSuccess());
        m.put("durationMs", d.getDurationMs());
        m.put("error", d.getError());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    private static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + HexFormat.of().formatHex(b);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long longVal(Object o, String field) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString().trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }
}
