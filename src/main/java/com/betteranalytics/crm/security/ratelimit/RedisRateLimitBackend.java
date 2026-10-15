package com.betteranalytics.crm.security.ratelimit;

import com.betteranalytics.crm.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-window counters in Redis (atomic INCR + PEXPIRE via a tiny Lua script),
 * shared by every API replica. Active only when app.redis.enabled=true.
 *
 * Fails open: if Redis is unreachable the call falls back to this instance's
 * in-memory Bucket4j buckets, so limits still apply per replica and an outage
 * never turns into "nobody can sign in".
 */
public class RedisRateLimitBackend implements RateLimitBackend {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitBackend.class);
    private static final String PREFIX = "crm:rl:";

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
            + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
            + "local t = redis.call('PTTL', KEYS[1]) "
            + "if t < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) t = tonumber(ARGV[1]) end "
            + "return {c, t}", List.class);

    private final StringRedisTemplate redis;
    private final RateLimitBackend fallback;
    private final AtomicLong lastWarnMs = new AtomicLong();

    public RedisRateLimitBackend(StringRedisTemplate redis, RateLimitBackend fallback) {
        this.redis = redis;
        this.fallback = fallback;
    }

    @Override
    public RateLimiter.Decision hit(String key, int limit, long windowMs) {
        try {
            @SuppressWarnings("unchecked")
            List<Object> r = redis.execute(SCRIPT, List.of(PREFIX + key),
                    String.valueOf(windowMs));
            long count = ((Number) r.get(0)).longValue();
            long ttlMs = Math.max(0, ((Number) r.get(1)).longValue());
            boolean allowed = count <= limit;
            long retry = allowed ? 0 : Math.max(1, (ttlMs + 999) / 1000);
            return new RateLimiter.Decision(allowed, limit, (int) Math.max(0, limit - count), retry);
        } catch (RuntimeException e) {
            warn(e);
            return fallback.hit(key, limit, windowMs);
        }
    }

    @Override
    public void reset(String key) {
        fallback.reset(key);
        try {
            redis.delete(PREFIX + key);
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    @Override
    public String name() {
        return "redis";
    }

    private void warn(RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if (now - last > 60_000 && lastWarnMs.compareAndSet(last, now)) {
            log.warn("Redis rate limiting unavailable, using per-instance limits: {}", e.getMessage());
        }
    }
}
