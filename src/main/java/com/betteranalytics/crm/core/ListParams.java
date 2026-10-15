package com.betteranalytics.crm.core;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pagination + sorting for list endpoints, resolved in the database.
 *
 *   page  — 0-based (default 0)
 *   size  — default 50, max 200
 *   limit — legacy alias for size (older clients); still clamped to 1000 as before
 *           so lookups like the company picker keep working
 *   sort  — "field", "field,asc|desc" or "-field"; only whitelisted fields
 *
 * Responses keep the existing {count, data} shape (count = total matches) and
 * add page / size.
 */
public final class ListParams {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;
    public static final int MAX_LEGACY_LIMIT = 1000;

    private ListParams() {}

    /**
     * @param sortable API sort key → entity attribute (whitelist)
     */
    public static Pageable pageable(Integer page, Integer size, Integer limit, String sort,
                                    Map<String, String> sortable, Sort defaultSort) {
        int p = page == null ? 0 : page;
        if (p < 0) throw new IllegalArgumentException("page must be 0 or greater");
        int s;
        if (size != null) s = Math.max(1, Math.min(size, MAX_SIZE));
        else if (limit != null) s = Math.max(1, Math.min(limit, MAX_LEGACY_LIMIT));
        else s = DEFAULT_SIZE;
        Sort order = parseSort(sort, sortable, defaultSort);
        return PageRequest.of(p, s, order);
    }

    static Sort parseSort(String sort, Map<String, String> sortable, Sort defaultSort) {
        if (sort == null || sort.isBlank()) return withIdTieBreak(defaultSort);
        String raw = sort.trim();
        Sort.Direction dir = Sort.Direction.ASC;
        if (raw.startsWith("-")) {
            dir = Sort.Direction.DESC;
            raw = raw.substring(1);
        }
        String[] parts = raw.split(",");
        String key = parts[0].trim();
        if (parts.length > 1) {
            String d = parts[1].trim().toLowerCase();
            if (d.equals("desc")) dir = Sort.Direction.DESC;
            else if (!d.equals("asc")) throw new IllegalArgumentException("sort direction must be asc or desc");
        }
        String attr = sortable.get(key);
        if (attr == null) throw new IllegalArgumentException("cannot sort by '" + key + "'; allowed: " + sortable.keySet());
        return withIdTieBreak(Sort.by(dir, attr));
    }

    /** A stable order across pages even when many rows share the sort value. */
    private static Sort withIdTieBreak(Sort s) {
        return s.getOrderFor("id") == null ? s.and(Sort.by(Sort.Direction.DESC, "id")) : s;
    }

    public static Map<String, Object> response(Page<?> page, List<?> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", page.getTotalElements());
        out.put("data", data);
        out.put("page", page.getNumber());
        out.put("size", page.getSize());
        return out;
    }

    /** Escapes LIKE wildcards in user input and wraps it as a lower-case contains pattern. */
    public static String likePattern(String search) {
        String s = search.trim().toLowerCase()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + s + "%";
    }
}
