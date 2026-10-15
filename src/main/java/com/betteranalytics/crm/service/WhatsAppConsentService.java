package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.WhatsAppConsent;
import com.betteranalytics.crm.entity.WhatsAppConsentEvent;
import com.betteranalytics.crm.repository.WhatsAppConsentEventRepository;
import com.betteranalytics.crm.repository.WhatsAppConsentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/**
 * WhatsApp opt-in / opt-out per (organization, E.164 number) with full history.
 *
 *   - inbound "STOP" / "UNSUBSCRIBE" (case-insensitive, whole message) opts out,
 *     inbound "START" opts back in (source = inbound_keyword);
 *   - users record manual changes with a reason (source = manual);
 *   - sending to an opted-out number is refused with IllegalStateException (409);
 *   - campaigns mark opted-out recipients skipped_opted_out instead of sending.
 *
 * Numbers with no row are treated as opted in (the business collected consent
 * when the number entered the CRM); only an explicit opt-out blocks sending.
 */
@Service
public class WhatsAppConsentService {

    public static final String SOURCE_KEYWORD = "inbound_keyword";
    public static final String SOURCE_MANUAL = "manual";
    private static final Set<String> STOP_WORDS = Set.of("STOP", "UNSUBSCRIBE", "STOPALL", "OPTOUT", "OPT-OUT");
    private static final Set<String> START_WORDS = Set.of("START", "SUBSCRIBE", "UNSTOP");

    private final WhatsAppConsentRepository consentRepo;
    private final WhatsAppConsentEventRepository eventRepo;
    private final TransactionTemplate tx;

    public WhatsAppConsentService(WhatsAppConsentRepository consentRepo,
                                  WhatsAppConsentEventRepository eventRepo,
                                  PlatformTransactionManager txManager) {
        this.consentRepo = consentRepo;
        this.eventRepo = eventRepo;
        this.tx = new TransactionTemplate(txManager);
    }

    /** opted_in | opted_out for an inbound keyword message, or null when it is not a keyword. */
    public static String keywordStatus(String body) {
        if (body == null) return null;
        String t = body.trim().toUpperCase(Locale.ROOT);
        if (t.endsWith(".") || t.endsWith("!")) t = t.substring(0, t.length() - 1).trim();
        if (STOP_WORDS.contains(t)) return WhatsAppConsent.OPTED_OUT;
        if (START_WORDS.contains(t)) return WhatsAppConsent.OPTED_IN;
        return null;
    }

    public boolean isOptedOut(Long orgId, String normalizedPhone) {
        if (normalizedPhone == null) return false;
        return consentRepo.findByOrganizationIdAndNormalizedPhone(orgId, normalizedPhone)
                .map(c -> WhatsAppConsent.OPTED_OUT.equals(c.getStatus())).orElse(false);
    }

    /** Opted-out subset of the given numbers, in one query. */
    public Set<String> optedOut(Long orgId, Collection<String> normalizedPhones) {
        List<String> phones = normalizedPhones.stream().filter(Objects::nonNull).distinct().toList();
        if (phones.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (int i = 0; i < phones.size(); i += 500) {
            out.addAll(consentRepo.findOptedOut(orgId, phones.subList(i, Math.min(phones.size(), i + 500))));
        }
        return out;
    }

    /** Throws IllegalStateException (409) when the number has opted out. */
    public void requireNotOptedOut(Long orgId, String normalizedPhone) {
        if (isOptedOut(orgId, normalizedPhone)) {
            throw new IllegalStateException("This number has opted out of WhatsApp messages (they replied STOP). "
                    + "Record a new opt-in with a reason before messaging it again.");
        }
    }

    /** Current state view: {phone, status, source, reason, updatedAt, updatedBy}. */
    public Map<String, Object> view(Long orgId, String normalizedPhone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phone", normalizedPhone);
        WhatsAppConsent c = consentRepo.findByOrganizationIdAndNormalizedPhone(orgId, normalizedPhone).orElse(null);
        m.put("status", c == null ? "unknown" : c.getStatus());
        m.put("optedOut", c != null && WhatsAppConsent.OPTED_OUT.equals(c.getStatus()));
        m.put("source", c == null ? null : c.getSource());
        m.put("reason", c == null ? null : c.getReason());
        m.put("updatedAt", c == null ? null : c.getUpdatedAt());
        m.put("updatedBy", c == null ? null : c.getUpdatedBy());
        return m;
    }

    public List<WhatsAppConsentEvent> history(Long orgId, String normalizedPhone, int limit) {
        return eventRepo.findByOrganizationIdAndNormalizedPhoneOrderByCreatedAtDescIdDesc(
                orgId, normalizedPhone, PageRequest.of(0, Math.max(1, Math.min(200, limit))));
    }

    /**
     * Sets the consent state and appends a history event, in one transaction.
     * A concurrent first insert for the same number is retried as an update.
     */
    public WhatsAppConsent record(Long orgId, String normalizedPhone, String status, String source,
                                  String reason, Long actorUserId, Long messageId) {
        if (!WhatsAppConsent.OPTED_IN.equals(status) && !WhatsAppConsent.OPTED_OUT.equals(status))
            throw new IllegalArgumentException("status must be opted_in or opted_out");
        String r = reason == null || reason.isBlank() ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason.trim());
        try {
            return tx.execute(s -> upsert(orgId, normalizedPhone, status, source, r, actorUserId, messageId));
        } catch (DataIntegrityViolationException race) {
            return tx.execute(s -> upsert(orgId, normalizedPhone, status, source, r, actorUserId, messageId));
        }
    }

    private WhatsAppConsent upsert(Long orgId, String phone, String status, String source, String reason,
                                   Long actorUserId, Long messageId) {
        Instant now = Instant.now();
        WhatsAppConsent c = consentRepo.findByOrganizationIdAndNormalizedPhone(orgId, phone).orElseGet(() -> {
            WhatsAppConsent n = new WhatsAppConsent();
            n.setOrganizationId(orgId);
            n.setNormalizedPhone(phone);
            n.setCreatedAt(now);
            return n;
        });
        c.setStatus(status);
        c.setSource(source);
        c.setReason(reason);
        c.setUpdatedBy(actorUserId);
        c.setUpdatedAt(now);
        c = consentRepo.saveAndFlush(c);

        WhatsAppConsentEvent e = new WhatsAppConsentEvent();
        e.setOrganizationId(orgId);
        e.setNormalizedPhone(phone);
        e.setStatus(status);
        e.setSource(source);
        e.setReason(reason);
        e.setActorUserId(actorUserId);
        e.setMessageId(messageId);
        e.setCreatedAt(now);
        eventRepo.save(e);
        return c;
    }
}
