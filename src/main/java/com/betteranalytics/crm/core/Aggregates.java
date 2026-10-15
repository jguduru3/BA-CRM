package com.betteranalytics.crm.core;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GROUP BY / COUNT / SUM queries over a Specification, so reports aggregate in
 * the database (one statement per figure) instead of loading whole tables.
 * The same scope Specifications as the list endpoints are reused, so a report
 * can never count a record its reader couldn't open.
 */
@Component
public class Aggregates {

    @PersistenceContext
    private EntityManager em;

    /** One row per distinct value of {@code groupAttr}: [count, sum(sumAttr) or 0]. */
    public record Bucket(long count, long sum) {}

    @Transactional(readOnly = true)
    public <T> Map<Object, Bucket> groupBy(Class<T> type, Specification<T> spec, String groupAttr, String sumAttr) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<T> root = q.from(type);
        Expression<Object> key = root.get(groupAttr);
        Expression<Long> sum = sumAttr == null ? cb.literal(0L) : cb.coalesce(cb.sum(root.<Long>get(sumAttr)), 0L);
        q.multiselect(key, cb.count(root), sum);
        Predicate p = spec == null ? null : spec.toPredicate(root, q, cb);
        if (p != null) q.where(p);
        q.groupBy(key);
        Map<Object, Bucket> out = new LinkedHashMap<>();
        List<Object[]> rows = em.createQuery(q).getResultList();
        for (Object[] r : rows) {
            out.put(r[0], new Bucket(((Number) r[1]).longValue(), r[2] == null ? 0L : ((Number) r[2]).longValue()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public <T> long count(Class<T> type, Specification<T> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<T> root = q.from(type);
        q.select(cb.count(root));
        Predicate p = spec == null ? null : spec.toPredicate(root, q, cb);
        if (p != null) q.where(p);
        return em.createQuery(q).getSingleResult();
    }
}
