package com.betteranalytics.crm.security.ratelimit;

import com.betteranalytics.crm.security.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bucket4j token buckets in this instance's memory: {@code limit} tokens that
 * refill all at once every {@code windowMs} (an interval refill, so the
 * behaviour matches a fixed window and Retry-After is exact).
 *
 * Idle buckets are swept opportunistically every few thousand hits, so the map
 * can't grow without bound and no scheduled job is needed.
 */
public class InMemoryRateLimitBackend implements RateLimitBackend {

    private static final long SWEEP_EVERY_HITS = 4096;
    private static final long MIN_IDLE_MS = 3_600_000;

    private record Entry(Bucket bucket, int limit, long windowMs, AtomicLong lastUsedMs) {}

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();

    @Override
    public RateLimiter.Decision hit(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        if (hits.incrementAndGet() % SWEEP_EVERY_HITS == 0) sweep(now);

        Entry e = buckets.compute(key, (k, existing) ->
                existing != null && existing.limit() == limit && existing.windowMs() == windowMs
                        ? existing
                        : new Entry(newBucket(limit, windowMs), limit, windowMs, new AtomicLong(now)));
        e.lastUsedMs().set(now);

        ConsumptionProbe probe = e.bucket().tryConsumeAndReturnRemaining(1);
        long retryAfter = probe.isConsumed() ? 0 : Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        return new RateLimiter.Decision(probe.isConsumed(), limit, (int) Math.max(0, probe.getRemainingTokens()), retryAfter);
    }

    @Override
    public void reset(String key) {
        buckets.remove(key);
    }

    @Override
    public String name() {
        return "memory";
    }

    int size() {
        return buckets.size();
    }

    private static Bucket newBucket(int limit, long windowMs) {
        Bandwidth bw = Bandwidth.builder()
                .capacity(limit)
                .refillIntervally(limit, Duration.ofMillis(windowMs))
                .build();
        return Bucket.builder().addLimit(bw).build();
    }

    private void sweep(long now) {
        buckets.entrySet().removeIf(en ->
                now - en.getValue().lastUsedMs().get() > Math.max(MIN_IDLE_MS, en.getValue().windowMs() * 2));
    }
}
