package com.betteranalytics.crm.security.ratelimit;

import com.betteranalytics.crm.security.RateLimiter;

/** Storage strategy behind {@link RateLimiter}. */
public interface RateLimitBackend {

    RateLimiter.Decision hit(String key, int limit, long windowMs);

    void reset(String key);

    String name();
}
