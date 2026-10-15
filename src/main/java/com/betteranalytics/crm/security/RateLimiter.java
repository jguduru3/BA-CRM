package com.betteranalytics.crm.security;

import com.betteranalytics.crm.security.ratelimit.InMemoryRateLimitBackend;
import com.betteranalytics.crm.security.ratelimit.RateLimitBackend;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Rate limiter keyed by an arbitrary string ("login:ip:1.2.3.4",
 * "login:email:a@b.c", "apikey:17", "ip:1.2.3.4").
 *
 * The API used by AuthController, ApiKeyAuthFilter and RateLimitFilter is
 * unchanged; the work is done by a {@link RateLimitBackend}:
 *   - default: Bucket4j token buckets held in this instance's memory
 *     ({@link InMemoryRateLimitBackend});
 *   - app.redis.enabled=true: a Redis fixed-window counter shared by every
 *     replica (RedisRateLimitBackend), so a client can't multiply its budget
 *     by the number of instances. It fails open to the in-memory buckets when
 *     Redis is unreachable — an outage must not lock everyone out.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {}

    private final RateLimitBackend backend;

    public RateLimiter(ObjectProvider<RateLimitBackend> backends) {
        this.backend = backends.getIfAvailable(InMemoryRateLimitBackend::new);
    }

    /** Counts one request against {@code limit} per {@code windowMs} for {@code key}. */
    public Decision hit(String key, int limit, long windowMs) {
        return backend.hit(key, limit, windowMs);
    }

    /** Forgets the key's history (e.g. after a successful sign-in). */
    public void reset(String key) {
        backend.reset(key);
    }

    /** Name of the active backend ("memory" or "redis") — for diagnostics and tests. */
    public String backendName() {
        return backend.name();
    }
}
