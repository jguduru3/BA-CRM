package com.betteranalytics.crm.config;

import com.betteranalytics.crm.permission.cache.NoopPermissionScopeCache;
import com.betteranalytics.crm.permission.cache.PermissionScopeCache;
import com.betteranalytics.crm.permission.cache.RedisPermissionScopeCache;
import com.betteranalytics.crm.security.ratelimit.InMemoryRateLimitBackend;
import com.betteranalytics.crm.security.ratelimit.RateLimitBackend;
import com.betteranalytics.crm.security.ratelimit.RedisRateLimitBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Optional Redis (app.redis.enabled / REDIS_ENABLED, REDIS_URL).
 *
 * Disabled (default): Spring's Redis auto-configuration is switched off
 * entirely by {@link RedisAutoConfigToggle} — no connection factory, no
 * connection attempts, no Redis health indicator — and the app uses
 * in-memory rate limits plus per-request permission memoisation.
 *
 * Enabled: rate limits are shared by all replicas and TEAM-scope permission
 * sets are cached for {@code app.redis.permission-cache-ttl-seconds} (60 s).
 */
@Configuration
public class RedisConfig {

    @Configuration
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
    static class Enabled {

        @Bean
        RateLimitBackend redisRateLimitBackend(StringRedisTemplate redis) {
            return new RedisRateLimitBackend(redis, new InMemoryRateLimitBackend());
        }

        @Bean
        PermissionScopeCache redisPermissionScopeCache(
                StringRedisTemplate redis,
                @Value("${app.redis.permission-cache-ttl-seconds:60}") long ttlSeconds) {
            return new RedisPermissionScopeCache(redis, Duration.ofSeconds(Math.max(1, ttlSeconds)));
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
    static class Disabled {

        @Bean
        RateLimitBackend inMemoryRateLimitBackend() {
            return new InMemoryRateLimitBackend();
        }

        @Bean
        PermissionScopeCache noopPermissionScopeCache() {
            return new NoopPermissionScopeCache();
        }
    }
}
