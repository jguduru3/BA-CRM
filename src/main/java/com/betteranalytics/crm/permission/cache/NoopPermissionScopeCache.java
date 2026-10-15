package com.betteranalytics.crm.permission.cache;

import java.util.Set;
import java.util.function.Supplier;

/** Default when Redis is disabled: always computes (per-request memoisation still applies). */
public class NoopPermissionScopeCache implements PermissionScopeCache {

    @Override
    public Set<Long> get(Long organizationId, String key, Supplier<Set<Long>> compute) {
        return compute.get();
    }

    @Override
    public void evictOrganization(Long organizationId) {
        // nothing cached
    }
}
