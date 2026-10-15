package com.betteranalytics.crm.core;

import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Reusable JPA Specifications for the CRM list / report / export queries. */
public final class Specs {

    private Specs() {}

    public static <T> Specification<T> org(Long orgId) {
        return (root, q, cb) -> cb.equal(root.get("organizationId"), orgId);
    }

    /** Record scope pushed into SQL: owner IN (scope) [OR owner IS NULL]. */
    public static <T> Specification<T> ownedWithin(OwnerScope scope, String ownerAttr) {
        return (root, q, cb) -> {
            if (scope.all()) return cb.conjunction();
            List<Predicate> any = new ArrayList<>();
            if (!scope.ownerIds().isEmpty()) any.add(root.get(ownerAttr).in(scope.ownerIds()));
            if (scope.includeUnowned()) any.add(cb.isNull(root.get(ownerAttr)));
            if (any.isEmpty()) return cb.disjunction();
            return cb.or(any.toArray(Predicate[]::new));
        };
    }

    /** Case-insensitive "contains" over several string attributes; blank search matches all. */
    public static <T> Specification<T> search(String search, String... attrs) {
        return (root, q, cb) -> {
            if (search == null || search.isBlank()) return cb.conjunction();
            String pattern = ListParams.likePattern(search);
            Predicate[] any = new Predicate[attrs.length];
            for (int i = 0; i < attrs.length; i++) {
                Expression<String> e = cb.lower(root.get(attrs[i]));
                any[i] = cb.like(e, pattern, '\\');
            }
            return cb.or(any);
        };
    }

    public static <T> Specification<T> eq(String attr, Object value) {
        return (root, q, cb) -> value == null ? cb.conjunction() : cb.equal(root.get(attr), value);
    }

    /** Case-insensitive equality on a string attribute; null/blank = no filter. */
    public static <T> Specification<T> eqIgnoreCase(String attr, String value) {
        return (root, q, cb) -> value == null || value.isBlank()
                ? cb.conjunction()
                : cb.equal(cb.lower(root.get(attr)), value.trim().toLowerCase());
    }

    public static <T> Specification<T> in(String attr, Collection<?> values) {
        return (root, q, cb) -> values == null || values.isEmpty() ? cb.conjunction() : root.get(attr).in(values);
    }

    public static <T> Specification<T> isNull(String attr) {
        return (root, q, cb) -> cb.isNull(root.get(attr));
    }

    public static <T> Specification<T> idGreaterThan(long id) {
        return (root, q, cb) -> cb.greaterThan(root.get("id"), id);
    }
}
