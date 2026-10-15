package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.Lead;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Evaluates campaign audience criteria IN THE DATABASE (JPA Criteria → one
 * COUNT and one LIMITed SELECT), never by loading the org's leads into memory.
 *
 * criteria JSON:
 *   { "entity": "lead",
 *     "filters": [ { "field": "stage",    "op": "eq",  "value":  "Qualified" },
 *                  { "field": "industry", "op": "in",  "values": ["Technology"] },
 *                  { "field": "value",    "op": "gte", "value":  100000 } ] }
 * Text fields compare case-insensitively; a NULL field never matches (same
 * semantics as the previous in-memory engine).
 */
@Service
public class CampaignAudienceService {

    public static final Set<String> FIELDS = Set.of("stage", "priority", "industry", "geography", "source", "value");
    public static final Set<String> OPS = Set.of("eq", "neq", "in", "gte", "lte", "gt", "lt");
    private static final int MAX_FILTERS = 50;
    private static final int MAX_IN_VALUES = 500;

    public record Filter(String field, String op, Object value, List<?> values) {}

    public record LeadRow(Long id, String name, String phone, Long ownerId) {}

    @PersistenceContext
    private EntityManager em;
    private final ObjectMapper objectMapper;

    public CampaignAudienceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Parses and validates criteria; any problem → IllegalArgumentException (400). Blank → no filters. */
    public List<Filter> parse(String criteriaJson, boolean required) {
        if (criteriaJson == null || criteriaJson.isBlank()) {
            if (required) throw new IllegalArgumentException("criteriaJson is required");
            return List.of();
        }
        Map<String, Object> criteria;
        try {
            criteria = objectMapper.readValue(criteriaJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("criteriaJson must be a JSON object");
        }
        if (criteria == null) throw new IllegalArgumentException("criteriaJson must be a JSON object");
        Object entity = criteria.get("entity");
        if (entity != null && !"lead".equals(entity))
            throw new IllegalArgumentException("criteriaJson.entity must be 'lead'");
        Object rawFilters = criteria.getOrDefault("filters", List.of());
        if (!(rawFilters instanceof List<?> list)) throw new IllegalArgumentException("criteriaJson.filters must be an array");
        if (list.size() > MAX_FILTERS) throw new IllegalArgumentException("Too many filters (max " + MAX_FILTERS + ")");
        List<Filter> filters = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("each filter must be an object");
            Object field = m.get("field");
            Object op = m.get("op") == null ? "eq" : m.get("op");
            if (!(field instanceof String fieldName) || !FIELDS.contains(fieldName))
                throw new IllegalArgumentException("filter.field must be one of " + new TreeSet<>(FIELDS));
            if (!(op instanceof String opName) || !OPS.contains(opName))
                throw new IllegalArgumentException("filter.op must be one of " + new TreeSet<>(OPS));
            List<?> values = m.get("values") instanceof List<?> l ? l : null;
            if ("in".equals(opName) && values == null)
                throw new IllegalArgumentException("filter.values must be an array for op 'in'");
            if (values != null && values.size() > MAX_IN_VALUES)
                throw new IllegalArgumentException("filter.values has too many entries (max " + MAX_IN_VALUES + ")");
            if ("value".equals(fieldName) && !"in".equals(opName) && toDouble(m.get("value")) == null)
                throw new IllegalArgumentException("filter.value must be a number for field 'value'");
            filters.add(new Filter(fieldName, opName, m.get("value"), values));
        }
        return filters;
    }

    /** Leads matching the filters (optionally only those with a phone number). */
    public long count(Long orgId, List<Filter> filters, boolean withPhoneOnly) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<Lead> r = q.from(Lead.class);
        q.select(cb.count(r)).where(predicates(cb, r, orgId, filters, withPhoneOnly, null));
        return em.createQuery(q).getSingleResult();
    }

    /** One page of matching leads with a phone, ordered by id, starting after {@code afterId}. */
    public List<LeadRow> page(Long orgId, List<Filter> filters, Long afterId, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<Lead> r = q.from(Lead.class);
        q.multiselect(r.get("id"), r.get("name"), r.get("phone"), r.get("ownerId"))
                .where(predicates(cb, r, orgId, filters, true, afterId))
                .orderBy(cb.asc(r.get("id")));
        return em.createQuery(q).setMaxResults(Math.max(1, limit)).getResultList().stream()
                .map(o -> new LeadRow((Long) o[0], (String) o[1], (String) o[2], (Long) o[3]))
                .toList();
    }

    /** Ids of matching leads (any phone), capped. */
    public List<Long> ids(Long orgId, List<Filter> filters, int limit) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<Lead> r = q.from(Lead.class);
        q.select(r.get("id")).where(predicates(cb, r, orgId, filters, false, null)).orderBy(cb.asc(r.get("id")));
        return em.createQuery(q).setMaxResults(Math.max(1, limit)).getResultList();
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<Lead> r, Long orgId, List<Filter> filters,
                                   boolean withPhoneOnly, Long afterId) {
        List<Predicate> ps = new ArrayList<>();
        ps.add(cb.equal(r.get("organizationId"), orgId));
        if (withPhoneOnly) {
            ps.add(cb.isNotNull(r.get("phone")));
            ps.add(cb.notEqual(cb.trim(r.<String>get("phone")), ""));
        }
        if (afterId != null) ps.add(cb.greaterThan(r.get("id"), afterId));
        for (Filter f : filters) ps.add(toPredicate(cb, r, f));
        return ps.toArray(new Predicate[0]);
    }

    private Predicate toPredicate(CriteriaBuilder cb, Root<Lead> r, Filter f) {
        if ("value".equals(f.field())) {
            Path<Long> p = r.get("value");
            Predicate notNull = cb.isNotNull(p);
            if ("in".equals(f.op())) {
                List<Long> vals = new ArrayList<>();
                for (Object v : f.values()) {
                    Double d = toDouble(v);
                    if (d != null && d == Math.rint(d)) vals.add(d.longValue());
                }
                return vals.isEmpty() ? cb.disjunction() : cb.and(notNull, p.in(vals));
            }
            double d = toDouble(f.value());
            return cb.and(notNull, switch (f.op()) {
                case "gte" -> cb.ge(p, (long) Math.ceil(d));
                case "gt"  -> cb.gt(p, (long) Math.floor(d));
                case "lte" -> cb.le(p, (long) Math.floor(d));
                case "lt"  -> cb.lt(p, (long) Math.ceil(d));
                case "eq"  -> d == Math.rint(d) ? cb.equal(p, (long) d) : cb.disjunction();
                case "neq" -> d == Math.rint(d) ? cb.notEqual(p, (long) d) : cb.conjunction();
                default    -> cb.disjunction();
            });
        }
        Expression<String> p = cb.lower(r.get(f.field()));
        Predicate notNull = cb.isNotNull(r.get(f.field()));
        return switch (f.op()) {
            case "eq" -> cb.and(notNull, cb.equal(p, lower(f.value())));
            case "neq" -> cb.and(notNull, cb.notEqual(p, lower(f.value())));
            case "in" -> {
                List<String> vals = f.values().stream().map(CampaignAudienceService::lower).toList();
                yield vals.isEmpty() ? cb.disjunction() : cb.and(notNull, p.in(vals));
            }
            // Range ops on text fields compare lexicographically (rarely useful, but well-defined).
            case "gte" -> cb.and(notNull, cb.greaterThanOrEqualTo(p, lower(f.value())));
            case "gt" -> cb.and(notNull, cb.greaterThan(p, lower(f.value())));
            case "lte" -> cb.and(notNull, cb.lessThanOrEqualTo(p, lower(f.value())));
            case "lt" -> cb.and(notNull, cb.lessThan(p, lower(f.value())));
            default -> cb.disjunction();
        };
    }

    private static String lower(Object v) {
        return String.valueOf(v).toLowerCase(Locale.ROOT);
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
