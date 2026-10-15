package com.betteranalytics.crm.permission.cache;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Cross-request cache for PermissionService's TEAM-scope owner-id sets
 * ("who answers to this manager", "whose records does this delegate cover").
 *
 * Without Redis this is a no-op ({@link NoopPermissionScopeCache}) — the sets
 * are still memoised per HTTP request inside PermissionService. With
 * app.redis.enabled=true they are shared by every replica for ~60 s
 * ({@link RedisPermissionScopeCache}) and dropped for the whole organization
 * whenever a team, user or delegation changes (AuditService calls
 * {@link #evictOrganization}).
 */
public interface PermissionScopeCache {

    Set<Long> get(Long organizationId, String key, Supplier<Set<Long>> compute);

    void evictOrganization(Long organizationId);
}
