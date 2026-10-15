package com.betteranalytics.crm.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Shared page/size handling for list endpoints that push pagination into the
 * database. {@code page} is zero-based. A missing size falls back to the
 * endpoint's default (and a legacy {@code limit} param when one is given);
 * sizes are clamped to 1..max so a client can never ask for an unbounded page.
 */
public final class Paging {

    private Paging() {}

    public static Pageable of(Integer page, Integer size, Integer legacyLimit, int defaultSize, int maxSize, Sort sort) {
        int s = size != null ? size : legacyLimit != null ? legacyLimit : defaultSize;
        s = Math.max(1, Math.min(s, maxSize));
        int p = page == null ? 0 : Math.max(0, page);
        return sort == null ? PageRequest.of(p, s) : PageRequest.of(p, s, sort);
    }

    public static Pageable of(Integer page, Integer size, int defaultSize, int maxSize, Sort sort) {
        return of(page, size, null, defaultSize, maxSize, sort);
    }
}
