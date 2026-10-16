package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.EmailConsent;
import com.betteranalytics.crm.entity.EmailConsentEvent;
import com.betteranalytics.crm.repository.EmailConsentEventRepository;
import com.betteranalytics.crm.repository.EmailConsentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Email opt-out per (organization, lowercase address) with full history — the
 * email counterpart of {@link WhatsAppConsentService}.
 *
 *   - campaign emails carry a List-Unsubscribe header (signed one-click link
 *     when app.public-base-url is set, plus a mailto: to the sending mailbox);
 *   - the link / RFC 8058 one-click POST, and replies whose subject or first
 *     line is "unsubscribe" / "stop" (seen by the inbox poller), opt out;
 *   - campaigns mark opted-out recipients skipped_opted_out, sequences skip
 *     their email steps.
 *
 * Addresses with no row are treated as opted in; only an explicit opt-out blocks.
 */
@Service
public class EmailConsentService {

    public static final String SOURCE_LINK = "unsubscribe_link";
    public static final String SOURCE_REPLY = "reply_keyword";
    public static final String SOURCE_MANUAL = "manual";
    private static final Pattern UNSUBSCRIBE_LINE = Pattern.compile(
            "^\\s*((re|aw|fwd?):\\s*)*(unsubscribe|stop|opt[ -]?out|remove me)[\\s.!]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{4,600}\\.[A-Za-z0-9_-]{20,100}$");

    private final EmailConsentRepository consentRepo;
    private final EmailConsentEventRepository eventRepo;
    private final TransactionTemplate tx;
    private final byte[] tokenKey;
    private final String publicBaseUrl;

    public EmailConsentService(EmailConsentRepository consentRepo,
                               EmailConsentEventRepository eventRepo,
                               PlatformTransactionManager txManager,
                               @Value("${app.encryption-key:}") String encryptionKey,
                               @Value("${app.jwt.secret:}") String jwtSecret,
                               @Value("${app.public-base-url:}") String publicBaseUrl) {
        this.consentRepo = consentRepo;
        this.eventRepo = eventRepo;
        this.tx = new TransactionTemplate(txManager);
        String seed = encryptionKey != null && !encryptionKey.isBlank() ? encryptionKey
                : (jwtSecret != null && !jwtSecret.isBlank() ? jwtSecret : null);
        byte[] key;
        if (seed == null) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);   // dev without secrets: links valid until restart
        } else {
            key = sha256(("email-unsubscribe:" + seed).getBytes(StandardCharsets.UTF_8));
        }
        this.tokenKey = key;
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.publicBaseUrl = base.startsWith("https://") || base.startsWith("http://") ? base : "";
    }

    /** Lowercase single address, or null when it is not one valid address. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String a = EmailIntegrationService.requireSingleAddress(raw).getAddress();
            if (a == null || a.indexOf('@') <= 0 || a.length() > 320) return null;
            return a.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when an inbound subject or the first non-blank body line is an unsubscribe request. */
    public static boolean isUnsubscribeRequest(String subject, String body) {
        if (subject != null && UNSUBSCRIBE_LINE.matcher(subject.trim()).matches()) return true;
        if (body == null) return false;
        for (String line : body.split("\\R", 20)) {
            if (line.isBlank()) continue;
            return UNSUBSCRIBE_LINE.matcher(line.trim()).matches();
        }
        return false;
    }

    public boolean isOptedOut(Long orgId, String emailNormalized) {
        if (emailNormalized == null) return false;
        return consentRepo.findByOrganizationIdAndEmailNormalized(orgId, emailNormalized)
                .map(c -> EmailConsent.OPTED_OUT.equals(c.getStatus())).orElse(false);
    }

    /** Opted-out subset of the given addresses, in one query per 500. */
    public Set<String> optedOut(Long orgId, Collection<String> emailsNormalized) {
        List<String> emails = emailsNormalized.stream().filter(Objects::nonNull).distinct().toList();
        if (emails.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (int i = 0; i < emails.size(); i += 500) {
            out.addAll(consentRepo.findOptedOut(orgId, emails.subList(i, Math.min(emails.size(), i + 500))));
        }
        return out;
    }

    /** Current state view: {email, status, optedOut, source, reason, updatedAt, updatedBy}. */
    public Map<String, Object> view(Long orgId, String emailNormalized) {
        EmailConsent c = consentRepo.findByOrganizationIdAndEmailNormalized(orgId, emailNormalized).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", emailNormalized);
        m.put("status", c == null ? "unknown" : c.getStatus());
        m.put("optedOut", c != null && EmailConsent.OPTED_OUT.equals(c.getStatus()));
        m.put("source", c == null ? null : c.getSource());
        m.put("reason", c == null ? null : c.getReason());
        m.put("updatedAt", c == null ? null : c.getUpdatedAt());
        m.put("updatedBy", c == null ? null : c.getUpdatedBy());
        return m;
    }

    public List<EmailConsentEvent> history(Long orgId, String emailNormalized, int limit) {
        return eventRepo.findByOrganizationIdAndEmailNormalizedOrderByCreatedAtDescIdDesc(
                orgId, emailNormalized, PageRequest.of(0, Math.max(1, Math.min(200, limit))));
    }

    /** Sets the state and appends a history event in one transaction (a first-insert race is retried). */
    public EmailConsent record(Long orgId, String email, String status, String source,
                               String reason, Long actorUserId, Long campaignId) {
        if (!EmailConsent.OPTED_IN.equals(status) && !EmailConsent.OPTED_OUT.equals(status))
            throw new IllegalArgumentException("status must be opted_in or opted_out");
        String normalized = normalize(email);
        if (normalized == null) throw new IllegalArgumentException("email is not a valid address");
        String r = reason == null || reason.isBlank() ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason.trim());
        try {
            return tx.execute(s -> upsert(orgId, normalized, status, source, r, actorUserId, campaignId));
        } catch (DataIntegrityViolationException race) {
            return tx.execute(s -> upsert(orgId, normalized, status, source, r, actorUserId, campaignId));
        }
    }

    private EmailConsent upsert(Long orgId, String email, String status, String source, String reason,
                                Long actorUserId, Long campaignId) {
        Instant now = Instant.now();
        EmailConsent c = consentRepo.findByOrganizationIdAndEmailNormalized(orgId, email).orElseGet(() -> {
            EmailConsent n = new EmailConsent();
            n.setOrganizationId(orgId);
            n.setEmailNormalized(email);
            n.setCreatedAt(now);
            return n;
        });
        c.setStatus(status);
        c.setSource(source);
        c.setReason(reason);
        c.setUpdatedBy(actorUserId);
        c.setUpdatedAt(now);
        c = consentRepo.saveAndFlush(c);

        EmailConsentEvent e = new EmailConsentEvent();
        e.setOrganizationId(orgId);
        e.setEmailNormalized(email);
        e.setStatus(status);
        e.setSource(source);
        e.setReason(reason);
        e.setActorUserId(actorUserId);
        e.setCampaignId(campaignId);
        e.setCreatedAt(now);
        eventRepo.save(e);
        return c;
    }

    // ── Signed unsubscribe tokens ────────────────────────────────────────────

    public record TokenClaims(Long organizationId, String email, Long campaignId) {}

    /** base64url("org:campaign:email") + "." + base64url(HMAC-SHA256). Never expires (unsubscribe must keep working). */
    public String token(Long orgId, String emailNormalized, Long campaignId) {
        String payload = orgId + ":" + (campaignId == null ? "" : campaignId) + ":" + emailNormalized;
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(p));
    }

    /** Claims of a valid token, or null (bad format or signature). */
    public TokenClaims verify(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return null;
        int dot = token.indexOf('.');
        String p = token.substring(0, dot);
        byte[] sig;
        String payload;
        try {
            sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(p), sig)) return null;
            payload = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] parts = payload.split(":", 3);
        if (parts.length != 3) return null;
        try {
            Long org = Long.parseLong(parts[0]);
            Long campaign = parts[1].isEmpty() ? null : Long.parseLong(parts[1]);
            String email = normalize(parts[2]);
            return email == null ? null : new TokenClaims(org, email, campaign);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Public one-click unsubscribe URL, or null when app.public-base-url is not configured. */
    public String unsubscribeUrl(Long orgId, String emailNormalized, Long campaignId) {
        if (publicBaseUrl.isEmpty()) return null;
        return publicBaseUrl + "/api/email/unsubscribe?t=" + token(orgId, emailNormalized, campaignId);
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
