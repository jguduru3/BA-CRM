package com.betteranalytics.crm.security.ratelimit;

import com.betteranalytics.crm.security.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimitBackendTest {

    @Test
    void allowsUpToTheLimitThenReportsRetryAfter() {
        InMemoryRateLimitBackend b = new InMemoryRateLimitBackend();
        for (int i = 0; i < 3; i++) {
            RateLimiter.Decision d = b.hit("k", 3, 60_000);
            assertThat(d.allowed()).isTrue();
            assertThat(d.remaining()).isEqualTo(2 - i);
        }
        RateLimiter.Decision blocked = b.hit("k", 3, 60_000);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void keysAreIndependentAndResettable() {
        InMemoryRateLimitBackend b = new InMemoryRateLimitBackend();
        b.hit("a", 1, 60_000);
        assertThat(b.hit("a", 1, 60_000).allowed()).isFalse();
        assertThat(b.hit("b", 1, 60_000).allowed()).isTrue();
        b.reset("a");
        assertThat(b.hit("a", 1, 60_000).allowed()).isTrue();
    }

    @Test
    void windowRefills() throws InterruptedException {
        InMemoryRateLimitBackend b = new InMemoryRateLimitBackend();
        assertThat(b.hit("w", 1, 50).allowed()).isTrue();
        assertThat(b.hit("w", 1, 50).allowed()).isFalse();
        Thread.sleep(80);
        assertThat(b.hit("w", 1, 50).allowed()).isTrue();
    }
}
