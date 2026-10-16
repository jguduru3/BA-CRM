package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.AppNotification;
import com.betteranalytics.crm.entity.DeviceToken;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AppNotificationRepository;
import com.betteranalytics.crm.repository.DeviceTokenRepository;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.PushService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-app notifications for the current user.
 *
 * GET    /api/notifications                       — list (unread first), ?limit= (1..500, default 50)
 * POST   /api/notifications/read-all              — mark all read
 * PATCH  /api/notifications/{id}                  — mark single read
 * GET    /api/notifications/unread-count          — badge count
 * POST   /api/notifications/device-tokens         — register this browser for web push
 * DELETE /api/notifications/device-tokens/{token} — unregister it (token = installation id)
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final int MAX_ENDPOINT_LENGTH = 1000;

    private final AppNotificationRepository notifRepo;
    private final DeviceTokenRepository     tokenRepo;
    private final CurrentUser               currentUser;
    private final PermissionService         permissionService;
    private final PushService               pushService;

    public NotificationController(AppNotificationRepository notifRepo,
                                  DeviceTokenRepository tokenRepo,
                                  CurrentUser currentUser,
                                  PermissionService permissionService,
                                  PushService pushService) {
        this.notifRepo = notifRepo;
        this.tokenRepo = tokenRepo;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.pushService = pushService;
    }

    /** Notifications are personal: OWN scope on NOTIFICATION, checked against the caller themself. */
    private User actor(Action action) {
        User actor = currentUser.get();
        permissionService.require(actor, action, ResourceType.NOTIFICATION, actor.getOrganizationId(), actor.getId());
        return actor;
    }

    /**
     * Whether web push (Azure Notification Hubs) is configured, plus the VAPID public
     * key the browser needs to subscribe — served at runtime so the SPA build stays
     * environment-independent. The client subscribes only when enabled.
     */
    @GetMapping("/push-status")
    public ResponseEntity<Map<String, Object>> pushStatus() {
        actor(Action.VIEW);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", pushService.isEnabled());
        out.put("provider", "azure-notification-hubs");
        out.put("vapidPublicKey", pushService.vapidPublicKey());
        return ResponseEntity.ok(out);
    }

    @GetMapping
    public ResponseEntity<List<AppNotification>> list(@RequestParam(defaultValue = "50") int limit) {
        User actor = actor(Action.VIEW);
        int capped = Math.max(1, Math.min(limit, 500));
        return ResponseEntity.ok(
                notifRepo.findByUserIdAndOrganizationIdOrderByReadAscCreatedAtDesc(
                        actor.getId(), actor.getOrganizationId(), PageRequest.of(0, capped)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        User actor = actor(Action.VIEW);
        long count = notifRepo.countByUserIdAndOrganizationIdAndReadFalse(actor.getId(), actor.getOrganizationId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PostMapping("/read-all")
    @Transactional
    public ResponseEntity<Map<String, Object>> readAll() {
        User actor = actor(Action.EDIT);
        int updated = notifRepo.markAllReadByUserId(actor.getId(), actor.getOrganizationId());
        return ResponseEntity.ok(Map.of("ok", true, "updated", updated));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AppNotification> markRead(@PathVariable Long id) {
        User actor = actor(Action.EDIT);
        AppNotification n = notifRepo.findByIdAndUserIdAndOrganizationId(id, actor.getId(), actor.getOrganizationId())
                .orElse(null);
        if (n == null) return ResponseEntity.notFound().build();
        n.setRead(true);
        if (n.getReadAt() == null) n.setReadAt(Instant.now());
        return ResponseEntity.ok(notifRepo.save(n));
    }

    // ── Device token management ───────────────────────────────────────────────

    /**
     * Tokens are device-bound: registering a token that is currently bound to a
     * different user (e.g. a shared device after a re-login) moves it to the
     * caller by deleting the old binding first — the old binding is never
     * read back or returned, so nothing about the previous user leaks.
     */
    @PostMapping("/device-tokens")
    @Transactional
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> registerToken(@RequestBody Map<String, Object> body) {
        User actor = actor(Action.EDIT);
        // Body: {"platform":"web","subscription":{"endpoint":"https://…","keys":{"p256dh":"…","auth":"…"}}}
        // — exactly what PushSubscription.toJSON() returns in the browser.
        Object subObj = body == null ? null : body.get("subscription");
        if (!(subObj instanceof Map<?, ?> sub)) {
            return ResponseEntity.badRequest().body(Map.of("error", "subscription is required"));
        }
        Object endpointObj = sub.get("endpoint");
        Object keysObj = sub.get("keys");
        if (!(endpointObj instanceof String endpoint) || !endpoint.startsWith("https://")
                || endpoint.length() > MAX_ENDPOINT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "subscription.endpoint must be an https URL"));
        }
        if (!(keysObj instanceof Map<?, ?> keys)
                || !(keys.get("p256dh") instanceof String p256dh) || p256dh.isBlank() || p256dh.length() > 200
                || !(keys.get("auth") instanceof String auth) || auth.isBlank() || auth.length() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "subscription.keys.p256dh and auth are required"));
        }
        String installationId = PushService.installationIdFor(endpoint);

        // Device-bound: a browser that re-subscribes after another user signed in on it moves to the caller.
        tokenRepo.deleteByTokenForOtherUsers(installationId, actor.getId());

        DeviceToken dt = tokenRepo.findByUserIdAndToken(actor.getId(), installationId).orElseGet(DeviceToken::new);
        dt.setOrganizationId(actor.getOrganizationId());
        dt.setUserId(actor.getId());
        dt.setToken(installationId);
        dt.setPlatform("web");
        dt.setActive(true);
        Instant now = Instant.now();
        if (dt.getCreatedAt() == null) dt.setCreatedAt(now);
        dt.setUpdatedAt(now);
        DeviceToken saved = tokenRepo.save(dt);

        // The hub call happens after commit so a failed save never leaves a stray installation.
        Long userId = actor.getId();
        afterCommit(() -> pushService.upsertBrowserInstallation(installationId, userId, endpoint, p256dh, auth));
        return ResponseEntity.ok(Map.of("id", saved.getId(), "token", installationId, "platform", "web"));
    }

    @DeleteMapping("/device-tokens/{token}")
    @Transactional
    public ResponseEntity<Void> removeToken(@PathVariable String token) {
        User actor = actor(Action.EDIT);
        boolean owned = tokenRepo.findByUserIdAndToken(actor.getId(), token).isPresent();
        tokenRepo.deleteByUserIdAndToken(actor.getId(), token);
        if (owned) afterCommit(() -> pushService.deleteInstallation(token));
        return ResponseEntity.noContent().build();
    }

    private static void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            r.run();
                        }
                    });
        } else {
            r.run();
        }
    }
}
