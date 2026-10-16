package com.betteranalytics.crm.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class SendThrottleTest {

    @Test
    void spacesSendsEvenlyPerKeyAcrossThreads() throws Exception {
        SendThrottle t = new SendThrottle();
        // 600/min = one slot every 100 ms; 6 acquisitions from 3 threads span >= 500 ms.
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> { t.acquire("mailbox:1", 600); return null; }));
        for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(480);

        // Another key is independent, and 0 disables throttling.
        long s2 = System.nanoTime();
        t.acquire("mailbox:2", 600);
        for (int i = 0; i < 100; i++) t.acquire("any", 0);
        assertThat((System.nanoTime() - s2) / 1_000_000).isLessThan(100);
    }
}
