package com.betteranalytics.crm.security;

import com.betteranalytics.crm.config.RedisConfig;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.cache.NoopPermissionScopeCache;
import com.betteranalytics.crm.permission.cache.PermissionScopeCache;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Login is throttled per client IP and per email (Bucket4j, in memory when Redis is off). */
class LoginRateLimitTest extends IntegrationTest {

    @Autowired RateLimiter rateLimiter;
    @Autowired PermissionScopeCache scopeCache;
    @Autowired ApplicationContext context;

    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(1, 250) + "." + r.nextInt(1, 250) + "." + r.nextInt(1, 250);
    }

    private ResultActions login(String ip, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .with(req -> { req.setRemoteAddr(ip); return req; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))));
    }

    @Test
    void perIpLimitReturns429WithRetryAfter() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 20; i++) {
            login(ip, "nobody-" + UUID.randomUUID() + "@x.example", "Wrong-pass-123")
                    .andExpect(status().isUnauthorized());
        }
        login(ip, "nobody-" + UUID.randomUUID() + "@x.example", "Wrong-pass-123")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // a different client is unaffected
        login(randomIp(), "nobody@x.example", "Wrong-pass-123").andExpect(status().isUnauthorized());
    }

    @Test
    void perEmailLimitAppliesAcrossIps() throws Exception {
        String email = "target-" + UUID.randomUUID() + "@x.example";
        for (int i = 0; i < 10; i++) {
            login(randomIp(), email, "Wrong-pass-123").andExpect(status().isUnauthorized());
        }
        ResultActions limited = login(randomIp(), email, "Wrong-pass-123")
                .andExpect(status().isTooManyRequests());
        long retry = Long.parseLong(limited.andReturn().getResponse().getHeader("Retry-After"));
        assertThat(retry).isBetween(1L, 300L);
    }

    @Test
    void successfulLoginResetsTheEmailCounter() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        for (int i = 0; i < 3; i++) login(randomIp(), u.getEmail(), "Wrong-pass-123");
        login(randomIp(), u.getEmail(), PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            login(randomIp(), u.getEmail(), "Wrong-pass-123").andExpect(status().isUnauthorized());
        }
    }

    @Test
    void redisIsOffByDefault() {
        assertThat(rateLimiter.backendName()).isEqualTo("memory");
        assertThat(scopeCache).isInstanceOf(NoopPermissionScopeCache.class);
        assertThat(context.getBeanNamesForType(
                org.springframework.data.redis.connection.RedisConnectionFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(RedisConfig.class)).hasSize(1);
    }
}
