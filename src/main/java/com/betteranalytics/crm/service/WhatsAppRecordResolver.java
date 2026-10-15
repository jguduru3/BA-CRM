package com.betteranalytics.crm.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Database lookups that connect WhatsApp traffic to CRM records, without
 * loading whole tables and without touching other domains' entity classes:
 *
 *   - {@link #findByPhone}: which Lead (then Contact) has this number — used to
 *     link inbound messages so the record owner can read them. Lead/Contact
 *     phones are free-form, so the comparison strips formatting inside the query
 *     (PhoneNumbers.SQL_STRIP_FORMAT); it is portable but not index-assisted.
 *   - {@link #owners}: owner ids of the records a page of messages links to, one
 *     query per record type (no N+1) — feeds the body field mask.
 */
@Component
public class WhatsAppRecordResolver {

    public record Link(String relatedType, Long relatedId, Long ownerId) {}

    @PersistenceContext
    private EntityManager em;

    public Optional<Link> findByPhone(Long orgId, String normalizedPhone) {
        if (normalizedPhone == null) return Optional.empty();
        String digits = PhoneNumbers.digits(normalizedPhone);
        List<String> variants = List.of("+" + digits, digits, "00" + digits);
        for (String[] t : new String[][]{{"Lead", "Lead"}, {"Contact", "Contact"}}) {
            List<Object[]> rows = em.createQuery(
                            "SELECT r.id, r.ownerId FROM " + t[1] + " r WHERE r.organizationId = :org AND "
                                    + String.format(PhoneNumbers.SQL_STRIP_FORMAT, "r.phone") + " IN :variants "
                                    + "ORDER BY r.id DESC", Object[].class)
                    .setParameter("org", orgId)
                    .setParameter("variants", variants)
                    .setMaxResults(1)
                    .getResultList();
            if (!rows.isEmpty()) {
                return Optional.of(new Link(t[0], (Long) rows.get(0)[0], (Long) rows.get(0)[1]));
            }
        }
        return Optional.empty();
    }

    /** relatedType → (relatedId → ownerId) for the given references, scoped to the org. */
    public Map<String, Map<Long, Long>> owners(Long orgId, Map<String, Set<Long>> idsByType) {
        Map<String, Map<Long, Long>> out = new HashMap<>();
        idsByType.forEach((type, ids) -> {
            String entity = switch (type) {
                case "Lead" -> "Lead";
                case "Contact" -> "Contact";
                case "Opportunity" -> "Opportunity";
                case "Campaign" -> "Campaign";
                default -> null;
            };
            if (entity == null || ids.isEmpty()) return;
            Map<Long, Long> m = new HashMap<>();
            em.createQuery("SELECT r.id, r.ownerId FROM " + entity + " r WHERE r.organizationId = :org AND r.id IN :ids",
                            Object[].class)
                    .setParameter("org", orgId)
                    .setParameter("ids", ids)
                    .getResultList()
                    .forEach(row -> m.put((Long) row[0], (Long) row[1]));
            out.put(type, m);
        });
        return out;
    }
}
