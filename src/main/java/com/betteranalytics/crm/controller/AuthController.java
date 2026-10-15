package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.dto.LoginRequest;
import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.JwtUtil;
import com.betteranalytics.crm.security.PasswordPolicy;
import com.betteranalytics.crm.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sign-in and session management.
 *
 * Brute-force defence is layered: a per-IP and a per-email request limit on
 * /login, plus an account lockout after repeated wrong passwords. Unknown
 * emails get the same response (and a comparable bcrypt cost) as wrong
 * passwords, so the endpoint can't be used to discover which emails exist.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String INVALID = "Invalid email or password";

    private final UserRepository userRepository;
    private final AuditEventRepository auditEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RateLimiter rateLimiter;
    private final int maxFailures;
    private final Duration lockDuration;
    /** Hash of a random string, compared against when the email is unknown to equalise timing. */
    private final String dummyHash;

    public AuthController(UserRepository userRepository, AuditEventRepository auditEventRepository,
                          PasswordEncoder passwordEncoder, JwtUtil jwtUtil, RateLimiter rateLimiter,
                          @Value("${app.auth.max-failed-logins:5}") int maxFailures,
                          @Value("${app.auth.lock-minutes:15}") int lockMinutes) {
        this.userRepository = userRepository;
        this.auditEventRepository = auditEventRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.rateLimiter = rateLimiter;
        this.maxFailures = maxFailures;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
        this.dummyHash = passwordEncoder.encode("timing-equaliser-" + System.nanoTime());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        if (req == null || req.email() == null || req.password() == null
                || req.email().isBlank() || req.password().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email and password are required"));
        }
        String email = req.email().trim().toLowerCase(Locale.ROOT);

        RateLimiter.Decision byIp = rateLimiter.hit("login:ip:" + ip, 20, 60_000);
        RateLimiter.Decision byEmail = rateLimiter.hit("login:email:" + email, 10, 300_000);
        if (!byIp.allowed() || !byEmail.allowed()) {
            long retry = Math.max(byIp.retryAfterSeconds(), byEmail.retryAfterSeconds());
            return ResponseEntity.status(429).header("Retry-After", String.valueOf(retry))
                    .body(Map.of("error", "Too many sign-in attempts. Try again in a few minutes.",
                                 "code", "RATE_LIMITED"));
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(req.password(), dummyHash);
            return unauthorized();
        }
        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            passwordEncoder.matches(req.password(), dummyHash);
            return ResponseEntity.status(423).body(Map.of(
                    "error", "This account is temporarily locked after repeated failed sign-ins. Try again later.",
                    "code", "ACCOUNT_LOCKED"));
        }
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            int failures = user.getFailedLoginCount() + 1;
            user.setFailedLoginCount(failures);
            if (failures >= maxFailures) {
                user.setLockedUntil(now.plus(lockDuration));
                user.setFailedLoginCount(0);
                audit(user, "account_locked", ip, http);
                log.warn("Account {} locked after {} failed sign-ins from {}", user.getId(), failures, ip);
            }
            userRepository.save(user);
            return unauthorized();
        }
        if (!user.isActive()) {
            // Same message as a wrong password — don't confirm the account exists.
            return unauthorized();
        }

        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userRepository.save(user);
        rateLimiter.reset("login:email:" + email);
        audit(user, "login", ip, http);

        Map<String, Object> body = new HashMap<>();
        body.put("token", jwtUtil.generateToken(user));
        body.put("expiresIn", jwtUtil.getExpirationMs() / 1000);
        body.put("user", user);
        body.put("mustChangePassword", user.isMustChangePassword());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(user);
    }

    /**
     * Self-service password change. Requires the current password (a stolen
     * token alone can't take over the account) and revokes every other session.
     * Returns a fresh token so the caller stays signed in.
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@AuthenticationPrincipal User principal,
                                            @RequestBody(required = false) Map<String, String> body,
                                            HttpServletRequest http) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "request body is required"));
        String current = body.get("currentPassword");
        String next = body.get("newPassword");
        User user = userRepository.findById(principal.getId()).orElseThrow();

        if (current == null || !passwordEncoder.matches(current, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "current password is incorrect"));
        }
        String problem = PasswordPolicy.validate(next, user.getEmail());
        if (problem != null) return ResponseEntity.badRequest().body(Map.of("error", problem));
        if (passwordEncoder.matches(next, user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "new password must differ from the current one"));
        }

        user.setPassword(passwordEncoder.encode(next));
        user.setMustChangePassword(false);
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        audit(user, "password_changed", http.getRemoteAddr(), http);
        return ResponseEntity.ok(Map.of("ok", true, "token", jwtUtil.generateToken(user), "user", user));
    }

    /** Revokes every token this user holds, on every device. */
    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(@AuthenticationPrincipal User principal, HttpServletRequest http) {
        User user = userRepository.findById(principal.getId()).orElseThrow();
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        audit(user, "logout_all", http.getRemoteAddr(), http);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", INVALID, "code", "INVALID_CREDENTIALS"));
    }

    private void audit(User user, String action, String ip, HttpServletRequest http) {
        try {
            AuditEvent e = new AuditEvent();
            e.setOrganizationId(user.getOrganizationId());
            e.setUserId(user.getId());
            e.setUserName(user.getName());
            e.setAction(action);
            e.setResource("auth");
            e.setResourceId(String.valueOf(user.getId()));
            e.setResourceName(user.getEmail());
            e.setIpAddress(ip);
            String ua = http.getHeader("User-Agent");
            e.setUserAgent(ua == null ? null : ua.substring(0, Math.min(ua.length(), 500)));
            e.setCreatedAt(Instant.now());
            auditEventRepository.save(e);
        } catch (Exception ex) {
            log.warn("could not write auth audit event {}: {}", action, ex.getMessage());
        }
    }
}
