package com.betteranalytics.crm.admin;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.security.RateLimiter;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.PushService;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The admin console API — one place for a Super Admin to see the health of the
 * organization and act on accounts. Everything is scoped to the caller's
 * organization; user CRUD itself stays on /api/users (same rules, reused by the UI).
 *
 *   GET  /api/admin/overview          users, records, integrations, 7-day security summary
 *   GET  /api/admin/users             users with security state (last sign-in, lock, pending reset)
 *   POST /api/admin/users/{id}/unlock        clear a brute-force lockout
 *   POST /api/admin/users/{id}/force-logout  revoke every session of that user
 *   GET  /api/admin/security-events   recent sign-in / lockout / denial / export events
 *   GET  /api/admin/system            version, schema version, runtime, enabled integrations
 *
 * Access goes through PermissionService (INTEGRATION rules = Super Admin only), and
 * every account action is written to the audit log.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** Audit actions surfaced in the security view. */
    static final List<String> SECURITY_ACTIONS = List.of(
            "login", "login_failed", "account_locked", "account_unlocked", "password_changed",
            "logout_all", "force_logout", "permission_denied", "export", "duplicate_bypass", "merge");

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final ObjectProvider<Flyway> flyway;
    private final PushService pushService;
    private final RateLimiter rateLimiter;
    private final String blobConnection;
    private final String appInsights;
    private final String whatsappSecret;

    public AdminController(UserRepository userRepository, PermissionService permissionService,
                           CurrentUser currentUser, AuditService auditService, JdbcTemplate jdbc,
                           Environment env, ObjectProvider<Flyway> flyway, PushService pushService,
                           RateLimiter rateLimiter,
                           @Value("${azure.blob.connection-string:}") String blobConnection,
                           @Value("${app.applicationinsights.connection-string:}") String appInsights,
                           @Value("${app.whatsapp.app-secret:}") String whatsappSecret) {
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.env = env;
        this.flyway = flyway;
        this.pushService = pushService;
        this.rateLimiter = rateLimiter;
        this.blobConnection = blobConnection;
        this.appInsights = appInsights;
        this.whatsappSecret = whatsappSecret;
    }

    // ── overview ─────────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        User admin = requireAdmin(Action.VIEW);
        Long org = admin.getOrganizationId();
        Instant now = Instant.now();
        Timestamp weekAgo = Timestamp.from(now.minus(7, ChronoUnit.DAYS));
        Timestamp nowTs = Timestamp.from(now);

        List<User> users = userRepository.findByOrganizationId(org);
        Map<String, Long> byRole = new LinkedHashMap<>();
        for (Role r : Role.values()) byRole.put(r.name(), 0L);
        long active = 0, locked = 0, pendingReset = 0, neverSignedIn = 0, activeLast7d = 0;
        for (User u : users) {
            if (u.isActive()) {
                active++;
                byRole.merge(u.getRole().name(), 1L, Long::sum);
            }
            if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)) locked++;
            if (u.isMustChangePassword()) pendingReset++;
            if (u.getLastLoginAt() == null) neverSignedIn++;
            else if (u.getLastLoginAt().isAfter(now.minus(7, ChronoUnit.DAYS))) activeLast7d++;
        }
        Map<String, Object> userStats = new LinkedHashMap<>();
        userStats.put("total", users.size());
        userStats.put("active", active);
        userStats.put("deactivated", users.size() - active);
        userStats.put("lockedNow", locked);
        userStats.put("pendingPasswordChange", pendingReset);
        userStats.put("neverSignedIn", neverSignedIn);
        userStats.put("activeLast7Days", activeLast7d);
        userStats.put("byRole", byRole);

        Map<String, Object> records = new LinkedHashMap<>();
        for (String t : List.of("leads", "opportunities", "companies", "contacts", "tasks")) {
            records.put(t, count("SELECT COUNT(*) FROM " + t + " WHERE organization_id = ? AND deleted_at IS NULL", org));
        }
        records.put("inTrash", count("""
                SELECT (SELECT COUNT(*) FROM leads WHERE organization_id = ? AND deleted_at IS NOT NULL)
                     + (SELECT COUNT(*) FROM opportunities WHERE organization_id = ? AND deleted_at IS NOT NULL)
                     + (SELECT COUNT(*) FROM companies WHERE organization_id = ? AND deleted_at IS NOT NULL)
                     + (SELECT COUNT(*) FROM contacts WHERE organization_id = ? AND deleted_at IS NOT NULL)
                     + (SELECT COUNT(*) FROM tasks WHERE organization_id = ? AND deleted_at IS NOT NULL)
                """, org, org, org, org, org));

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("attachments", count("SELECT COUNT(*) FROM attachments WHERE organization_id = ?", org));
        storage.put("bytes", count("SELECT COALESCE(SUM(size_bytes), 0) FROM attachments WHERE organization_id = ?", org));
        storage.put("backend", blank(blobConnection) ? "local" : "azure_blob");

        Map<String, Object> integrations = new LinkedHashMap<>();
        integrations.put("apiKeysActive", count("""
                SELECT COUNT(*) FROM api_keys WHERE organization_id = ? AND revoked_at IS NULL
                   AND (expires_at IS NULL OR expires_at > ?)""", org, nowTs));
        integrations.put("webhooksActive", count(
                "SELECT COUNT(*) FROM webhook_subscriptions WHERE organization_id = ? AND is_active = TRUE", org));
        integrations.put("webhooksFailing", count(
                "SELECT COUNT(*) FROM webhook_subscriptions WHERE organization_id = ? AND consecutive_failures > 0", org));
        integrations.put("aiConfigured", count(
                "SELECT COUNT(*) FROM org_settings WHERE organization_id = ? AND ai_enabled = TRUE AND ai_api_key IS NOT NULL", org) > 0);

        Map<String, Long> security = new LinkedHashMap<>();
        for (String a : SECURITY_ACTIONS) security.put(a, 0L);
        jdbc.query("""
                SELECT action, COUNT(*) AS n FROM audit_events
                 WHERE organization_id = ? AND created_at >= ? GROUP BY action""",
                rs -> {
                    String action = rs.getString("action");
                    if (security.containsKey(action)) security.put(action, rs.getLong("n"));
                }, org, weekAgo);

        List<Map<String, Object>> activity = new ArrayList<>();
        jdbc.query("""
                SELECT CAST(created_at AS DATE) AS d, COUNT(*) AS n FROM audit_events
                 WHERE organization_id = ? AND created_at >= ? GROUP BY CAST(created_at AS DATE) ORDER BY d""",
                rs -> {
                    activity.add(Map.of("date", rs.getDate("d").toLocalDate().toString(), "events", rs.getLong("n")));
                }, org, weekAgo);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", userStats);
        out.put("records", records);
        out.put("storage", storage);
        out.put("integrations", integrations);
        out.put("securityLast7Days", security);
        out.put("activityLast7Days", activity);
        out.put("generatedAt", now);
        return out;
    }

    // ── users ────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        User admin = requireAdmin(Action.VIEW);
        Instant now = Instant.now();
        return userRepository.findByOrganizationId(admin.getOrganizationId()).stream()
                .sorted(Comparator.comparing(User::isActive).reversed().thenComparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("name", u.getName());
                    m.put("email", u.getEmail());
                    m.put("role", u.getRole().name());
                    m.put("active", u.isActive());
                    m.put("initials", u.getInitials());
                    m.put("color", u.getColor());
                    m.put("reportsToId", u.getReportsToId());
                    m.put("territory", u.getTerritory());
                    m.put("lastLoginAt", u.getLastLoginAt());
                    m.put("createdAt", u.getCreatedAt());
                    m.put("mustChangePassword", u.isMustChangePassword());
                    boolean locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(now);
                    m.put("locked", locked);
                    m.put("lockedUntil", locked ? u.getLockedUntil() : null);
                    m.put("failedLoginCount", u.getFailedLoginCount());
                    return m;
                }).toList();
    }

    @PostMapping("/users/{id}/unlock")
    @Transactional
    public Map<String, Object> unlock(@PathVariable Long id) {
        User admin = requireAdmin(Action.EDIT);
        User u = target(admin, id);
        u.setLockedUntil(null);
        u.setFailedLoginCount(0);
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
        auditService.log("account_unlocked", "users", u.getId(), u.getName(), null, Map.of("unlockedBy", admin.getId()));
        return Map.of("ok", true);
    }

    /** Revokes every token the user holds (all devices). Takes effect on their next request. */
    @PostMapping("/users/{id}/force-logout")
    @Transactional
    public Map<String, Object> forceLogout(@PathVariable Long id) {
        User admin = requireAdmin(Action.EDIT);
        User u = target(admin, id);
        u.setTokenVersion(u.getTokenVersion() + 1);
        u.setUpdatedAt(Instant.now());
        userRepository.save(u);
        auditService.log("force_logout", "users", u.getId(), u.getName(), null, Map.of("by", admin.getId()));
        return Map.of("ok", true);
    }

    // ── security events ──────────────────────────────────────────────────────

    @GetMapping("/security-events")
    public Map<String, Object> securityEvents(@RequestParam(defaultValue = "30") int days,
                                              @RequestParam(required = false) String action,
                                              @RequestParam(defaultValue = "100") int limit) {
        User admin = requireAdmin(Action.VIEW);
        int span = Math.max(1, Math.min(days, 365));
        int size = Math.max(1, Math.min(limit, 500));
        List<String> actions = action == null || action.isBlank() ? SECURITY_ACTIONS
                : SECURITY_ACTIONS.contains(action) ? List.of(action) : null;
        if (actions == null) throw new IllegalArgumentException("unknown security action: " + action);

        String in = String.join(",", Collections.nCopies(actions.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(admin.getOrganizationId());
        args.add(Timestamp.from(Instant.now().minus(span, ChronoUnit.DAYS)));
        args.addAll(actions);
        args.add(size);
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, action, resource, resource_id, resource_name, user_id, user_name,
                       ip_address, user_agent, created_at
                  FROM audit_events
                 WHERE organization_id = ? AND created_at >= ? AND action IN (""" + in + """
                )
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?""",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("action", rs.getString("action"));
                    m.put("resource", rs.getString("resource"));
                    m.put("resourceId", rs.getString("resource_id"));
                    m.put("resourceName", rs.getString("resource_name"));
                    m.put("userId", rs.getObject("user_id"));
                    m.put("userName", rs.getString("user_name"));
                    m.put("ipAddress", rs.getString("ip_address"));
                    m.put("userAgent", rs.getString("user_agent"));
                    Timestamp ts = rs.getTimestamp("created_at");
                    m.put("createdAt", ts == null ? null : ts.toInstant());
                    return m;
                }, args.toArray());
        return Map.of("days", span, "actions", SECURITY_ACTIONS, "data", rows);
    }

    // ── system ───────────────────────────────────────────────────────────────

    @GetMapping("/system")
    public Map<String, Object> system() {
        requireAdmin(Action.VIEW);
        Map<String, Object> out = new LinkedHashMap<>();
        String version = getClass().getPackage().getImplementationVersion();
        out.put("version", version == null ? "1.0.0" : version);
        out.put("profiles", env.getActiveProfiles().length == 0 ? List.of(env.getDefaultProfiles())
                : List.of(env.getActiveProfiles()));
        Flyway fw = flyway.getIfAvailable();
        if (fw != null && fw.info().current() != null) {
            out.put("schemaVersion", fw.info().current().getVersion().getVersion());
            out.put("schemaDescription", fw.info().current().getDescription());
            out.put("pendingMigrations", fw.info().pending().length);
        }
        out.put("java", System.getProperty("java.version"));
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        out.put("uptimeSeconds", Duration.ofMillis(uptimeMs).toSeconds());
        Runtime rt = Runtime.getRuntime();
        out.put("memory", Map.of("usedMb", (rt.totalMemory() - rt.freeMemory()) / 1_048_576, "maxMb", rt.maxMemory() / 1_048_576));

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("fileStorage", blank(blobConnection) ? "Local disk" : "Azure Blob Storage");
        features.put("webPush", pushService.isEnabled() ? "Azure Notification Hubs" : null);
        features.put("rateLimiting", "redis".equals(rateLimiter.backendName()) ? "Redis (shared)" : "In-memory (per instance)");
        features.put("applicationInsights", !blank(appInsights));
        features.put("whatsappSignedWebhooks", !blank(whatsappSecret));
        features.put("apiDocs", env.getProperty("springdoc.api-docs.enabled", Boolean.class, true));
        out.put("features", features);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User requireAdmin(Action action) {
        User actor = currentUser.get();
        // INTEGRATION rules = Super Admin only; reused so there's one definition of "admin".
        if (!permissionService.can(actor, action, ResourceType.INTEGRATION, actor.getOrganizationId(), actor.getId())) {
            throw new AccessDeniedException("the admin console is available to Super Admins only");
        }
        return actor;
    }

    private User target(User admin, Long id) {
        return userRepository.findByIdAndOrganizationId(id, admin.getOrganizationId())
                .orElseThrow(() -> new NoSuchElementException("user not found"));
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
