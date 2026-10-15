package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.AppNotification;
import com.betteranalytics.crm.entity.DeviceToken;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AppNotificationRepository;
import com.betteranalytics.crm.repository.DeviceTokenRepository;
import com.betteranalytics.crm.security.CurrentUser;
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
 * POST   /api/notifications/device-tokens         — register FCM token
 * DELETE /api/notifications/device-tokens/{token} — remove FCM token
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Set<String> PLATFORMS = Set.of("android", "ios", "web");
    private static final int MAX_TOKEN_LENGTH = 500;

    private final AppNotificationRepository notifRepo;
    private final DeviceTokenRepository     tokenRepo;
    private final CurrentUser               currentUser;

    public NotificationController(AppNotificationRepository notifRepo,
                                   DeviceTokenRepository tokenRepo,
                                   CurrentUser currentUser) {
        this.notifRepo = notifRepo;
        this.tokenRepo = tokenRepo;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ResponseEntity<List<AppNotification>> list(@RequestParam(defaultValue = "50") int limit) {
        User actor = currentUser.get();
        int capped = Math.max(1, Math.min(limit, 500));
        return ResponseEntity.ok(
                notifRepo.findByUserIdAndOrganizationIdOrderByReadAscCreatedAtDesc(
                        actor.getId(), actor.getOrganizationId(), PageRequest.of(0, capped)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        User actor = currentUser.get();
        long count = notifRepo.countByUserIdAndOrganizationIdAndReadFalse(actor.getId(), actor.getOrganizationId());
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PostMapping("/read-all")
    @Transactional
    public ResponseEntity<Map<String, Object>> readAll() {
        User actor = currentUser.get();
        int updated = notifRepo.markAllReadByUserId(actor.getId(), actor.getOrganizationId());
        return ResponseEntity.ok(Map.of("ok", true, "updated", updated));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<AppNotification> markRead(@PathVariable Long id) {
        User actor = currentUser.get();
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
    public ResponseEntity<?> registerToken(@RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        Object tokenObj = body == null ? null : body.get("token");
        Object platformObj = body == null ? null : body.get("platform");
        if (!(tokenObj instanceof String token) || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "token is required"));
        }
        if (platformObj != null && !(platformObj instanceof String)) {
            return ResponseEntity.badRequest().body(Map.of("error", "platform must be a string"));
        }
        String platform = platformObj != null ? (String) platformObj : "web";
        if (!PLATFORMS.contains(platform)) {
            return ResponseEntity.badRequest().body(Map.of("error", "platform must be one of android, ios, web"));
        }

        tokenRepo.deleteByTokenForOtherUsers(token, actor.getId());

        DeviceToken dt = tokenRepo.findByUserIdAndToken(actor.getId(), token).orElseGet(DeviceToken::new);
        dt.setOrganizationId(actor.getOrganizationId());
        dt.setUserId(actor.getId());
        dt.setToken(token);
        dt.setPlatform(platform);
        dt.setActive(true);
        Instant now = Instant.now();
        if (dt.getCreatedAt() == null) dt.setCreatedAt(now);
        dt.setUpdatedAt(now);
        return ResponseEntity.ok(tokenRepo.save(dt));
    }

    @DeleteMapping("/device-tokens/{token}")
    @Transactional
    public ResponseEntity<Void> removeToken(@PathVariable String token) {
        User actor = currentUser.get();
        tokenRepo.deleteByUserIdAndToken(actor.getId(), token);
        return ResponseEntity.noContent().build();
    }
}
