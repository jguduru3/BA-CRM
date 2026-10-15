package com.betteranalytics.crm.permission.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Redis-backed scope cache. Entries are namespaced by a per-organization
 * generation number; evicting an organization just increments it, so every
 * replica stops reading the old entries at once (they then expire on their
 * own TTL). Any Redis error falls back to computing the set — correctness
 * never depends on the cache.
 */
public class RedisPermissionScopeCache implements PermissionScopeCache {

    private static final Logger log = LoggerFactory.getLogger(RedisPermissionScopeCache.class);
    private static final String PREFIX = "crm:perm:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisPermissionScopeCache(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Set<Long> get(Long organizationId, String key, Supplier<Set<Long>> compute) {
        if (organizationId == null) return compute.get();
        String entryKey;
        try {
            String gen = redis.opsForValue().get(genKey(organizationId));
            entryKey = PREFIX + organizationId + ":" + (gen == null ? "0" : gen) + ":" + key;
            String hit = redis.opsForValue().get(entryKey);
            if (hit != null) return decode(hit);
        } catch (RuntimeException e) {
            log.debug("permission cache read failed: {}", e.getMessage());
            return compute.get();
        }
        Set<Long> value = compute.get();
        try {
            redis.opsForValue().set(entryKey, encode(value), ttl);
        } catch (RuntimeException e) {
            log.debug("permission cache write failed: {}", e.getMessage());
        }
        return value;
    }

    @Override
    public void evictOrganization(Long organizationId) {
        if (organizationId == null) return;
        try {
            redis.opsForValue().increment(genKey(organizationId));
        } catch (RuntimeException e) {
            log.warn("permission cache eviction failed for org {}: {}", organizationId, e.getMessage());
        }
    }

    private static String genKey(Long organizationId) {
        return PREFIX + "gen:" + organizationId;
    }

    static String encode(Set<Long> ids) {
        return ids.isEmpty() ? "-" : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    static Set<Long> decode(String s) {
        if (s.equals("-") || s.isEmpty()) return Set.of();
        return Arrays.stream(s.split(",")).map(Long::valueOf).collect(Collectors.toCollection(HashSet::new));
    }
}
