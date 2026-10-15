package com.betteranalytics.crm.security;

import com.betteranalytics.crm.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Set;

@Component
public class JwtUtil {

    static final String ISSUER = "better-crm";

    /** Secrets that have shipped in this repo's config/docs — never acceptable in production. */
    private static final Set<String> KNOWN_DEFAULTS = Set.of(
            "change_this_to_a_long_random_string_in_production",
            "change_me_in_production_use_32plus_chars",
            "change_me_to_a_long_random_string_at_least_32_chars");

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs,
            @Value("${app.jwt.enforce-strong-secret:true}") boolean enforceStrongSecret
    ) {
        if (enforceStrongSecret && !isStrong(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to a random value of at least 32 bytes "
                    + "(generate one with: openssl rand -hex 32)");
        }
        // Derive a full-entropy 256-bit HMAC key from the configured secret instead
        // of zero-padding short secrets (padding added no entropy at all).
        this.key = Keys.hmacShaKeyFor(sha256(secret == null ? "" : secret));
        this.expirationMs = expirationMs;
    }

    /** At least 32 bytes (256 bits of HMAC key material) and not a value that ever shipped in this repo. */
    static boolean isStrong(String secret) {
        return secret != null
                && secret.getBytes(StandardCharsets.UTF_8).length >= 32
                && !KNOWN_DEFAULTS.contains(secret.trim())
                && !secret.trim().toLowerCase().startsWith("change_me");
    }

    public String generateToken(User user) {
        Date now = new Date();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim("orgId", user.getOrganizationId())
                .claim("ver", user.getTokenVersion())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).requireIssuer(ISSUER).build()
                .parseSignedClaims(token).getPayload();
    }

    public long getExpirationMs() { return expirationMs; }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
