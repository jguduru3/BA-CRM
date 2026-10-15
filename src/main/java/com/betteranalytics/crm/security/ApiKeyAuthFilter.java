package com.betteranalytics.crm.security;

import com.betteranalytics.crm.entity.ApiKey;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.ApiKeyRepository;
import com.betteranalytics.crm.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Authenticates Master API calls made with an API key, sent either as
 * "X-API-Key: bcrm_…" or "Authorization: Bearer bcrm_…".
 *
 * Keys are accepted ONLY under /api/master/** — the rest of the API stays
 * JWT-only, so the Master API is the single, auditable integration surface.
 * The request then runs as the key's acting user, so every existing
 * PermissionService rule (tenant → role → record scope → field mask) applies
 * unchanged. The key itself is exposed as a request attribute so the gateway
 * can enforce read-only scope.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String KEY_PREFIX = "bcrm_";
    public static final String ATTR_API_KEY = "crm.apiKey";
    public static final String MASTER_PREFIX = "/api/master";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;
    private final RateLimiter rateLimiter;
    private final int perMinute;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository, UserRepository userRepository,
                            RateLimiter rateLimiter,
                            @Value("${app.rate-limit.api-key-per-minute:600}") int perMinute) {
        this.apiKeyRepository = apiKeyRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.equals(MASTER_PREFIX) || uri.startsWith(MASTER_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String raw = extractKey(request);
        if (raw == null) {
            chain.doFilter(request, response); // may still be a JWT session (e.g. the in-app API console)
            return;
        }

        Instant now = Instant.now();
        ApiKey key = apiKeyRepository.findByKeyHash(hash(raw)).orElse(null);
        if (key == null || !key.usableAt(now)) {
            JsonErrors.write(response, 401, "INVALID_API_KEY", "API key is invalid, expired or revoked.");
            return;
        }
        User actor = userRepository.findById(key.getActingUserId()).orElse(null);
        if (actor == null || !actor.isActive() || !actor.getOrganizationId().equals(key.getOrganizationId())) {
            JsonErrors.write(response, 401, "INVALID_API_KEY", "The user this API key acts as is no longer active.");
            return;
        }

        RateLimiter.Decision d = rateLimiter.hit("apikey:" + key.getId(), perMinute, 60_000);
        response.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));
        if (!d.allowed()) {
            response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
            JsonErrors.write(response, 429, "RATE_LIMITED", "API key rate limit exceeded; retry later.");
            return;
        }

        try {
            apiKeyRepository.recordUse(key.getId(), now, request.getRemoteAddr());
        } catch (Exception e) {
            log.debug("could not record API key usage for key {}: {}", key.getId(), e.getMessage());
        }

        var auth = new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        request.setAttribute(ATTR_API_KEY, key);
        chain.doFilter(request, response);
    }

    private static String extractKey(HttpServletRequest request) {
        String header = request.getHeader("X-API-Key");
        if (header != null && !header.isBlank()) return header.trim();
        String authz = request.getHeader("Authorization");
        if (authz != null && authz.startsWith("Bearer ")) {
            String token = authz.substring(7).trim();
            if (token.startsWith(KEY_PREFIX)) return token;
        }
        return null;
    }

    /** New random key: "bcrm_" + 40 base62 chars (~238 bits). */
    public static String generateKey() {
        StringBuilder sb = new StringBuilder(KEY_PREFIX);
        for (int i = 0; i < 40; i++) sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        return sb.toString();
    }

    public static String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
