package com.betteranalytics.crm.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Evenly spaced send slots per key (e.g. one mailbox, the SMS sender): at most
 * {@code perMinute} sends per minute, one every 60s / perMinute. A caller blocks
 * until its slot. Slots are reserved atomically, so concurrent workers sharing a
 * key never exceed the rate together. Per JVM (each instance throttles itself).
 * {@code perMinute <= 0} disables throttling.
 */
@Component
public class SendThrottle {

    private final ConcurrentHashMap<String, Long> nextSlot = new ConcurrentHashMap<>();

    /** Waits for the next slot of {@code key}; returns the milliseconds waited. */
    public long acquire(String key, int perMinute) throws InterruptedException {
        if (perMinute <= 0) return 0;
        long intervalNanos = 60_000_000_000L / perMinute;
        long[] slot = new long[1];
        nextSlot.compute(key, (k, next) -> {
            long now = System.nanoTime();
            long s = next == null || next - now < 0 ? now : next;
            slot[0] = s;
            return s + intervalNanos;
        });
        long waitNanos = slot[0] - System.nanoTime();
        if (waitNanos <= 0) return 0;
        long ms = waitNanos / 1_000_000;
        Thread.sleep(ms, (int) (waitNanos % 1_000_000));
        return ms;
    }
}
