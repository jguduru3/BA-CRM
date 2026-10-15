package com.betteranalytics.crm.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window rate limiter keyed by an arbitrary string
 * ("login:ip:1.2.3.4", "apikey:17", ...). Per-instance — good enough to stop
 * brute force and runaway integrations on a single replica; put a shared store
 * (Redis) behind this interface before scaling the API horizontally.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {}

    private static final class Window {
        long startMs;
        int count;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public Decision hit(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        Window w = windows.computeIfAbsent(key, k -> new Window());
        synchronized (w) {
            if (now - w.startMs >= windowMs) {
                w.startMs = now;
                w.count = 0;
            }
            w.count++;
            boolean allowed = w.count <= limit;
            long retryAfter = allowed ? 0 : Math.max(1, (w.startMs + windowMs - now + 999) / 1000);
            return new Decision(allowed, limit, Math.max(0, limit - w.count), retryAfter);
        }
    }

    public void reset(String key) {
        windows.remove(key);
    }

    /** Drop idle windows so the map can't grow without bound. */
    @Scheduled(fixedDelay = 300_000)
    public void evictIdle() {
        long cutoff = System.currentTimeMillis() - 3_600_000;
        windows.entrySet().removeIf(e -> e.getValue().startMs < cutoff);
    }
}
