package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.permission.cache.NoopPermissionScopeCache;
import com.betteranalytics.crm.permission.cache.PermissionScopeCache;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.security.ApiKeyAuthFilter;
import com.betteranalytics.crm.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Called explicitly from every mutating controller method, so audit coverage
 * doesn't depend on remembering to attach a middleware per-route.
 *
 * Each audited create/update/delete on a published resource is also emitted as
 * an outbound webhook event (WebhookService) — the audit trail is the single
 * source of "something changed", so the two can never drift apart.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository auditEventRepository;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final WebhookService webhookService;
    private final PermissionScopeCache scopeCache;

    /** Changes to these resources reshape TEAM scope, so cached scope sets must be dropped. */
    private static final Set<String> SCOPE_RESOURCES = Set.of("teams", "users", "delegations");

    public AuditService(AuditEventRepository auditEventRepository, CurrentUser currentUser,
                        ObjectMapper objectMapper, WebhookService webhookService,
                        ObjectProvider<PermissionScopeCache> scopeCache) {
        this.auditEventRepository = auditEventRepository;
        this.currentUser = currentUser;
        // Spring's ObjectMapper has JavaTimeModule registered — a bare `new ObjectMapper()`
        // failed on every entity's Instant fields, silently nulling every snapshot.
        this.objectMapper = objectMapper;
        this.webhookService = webhookService;
        this.scopeCache = scopeCache.getIfAvailable(NoopPermissionScopeCache::new);
    }

    public void log(String action, String resource, Object resourceId, String resourceName, Object before, Object after) {
        AuditEvent event = new AuditEvent();
        event.setOrganizationId(currentUser.organizationId());
        event.setUserId(currentUser.userId());
        event.setUserName(currentUser.get().getName());
        event.setAction(action);
        event.setResource(resource);
        event.setResourceId(resourceId == null ? null : String.valueOf(resourceId));
        event.setResourceName(resourceName == null ? null : truncate(resourceName, 300));
        event.setBeforeJson(toJson(before));
        event.setAfterJson(toJson(after));
        event.setCreatedAt(Instant.now());

        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            event.setIpAddress(request.getRemoteAddr());
            String ua = request.getHeader("User-Agent");
            if (request.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY) != null) {
                ua = "[master-api] " + (ua == null ? "" : ua);
            }
            event.setUserAgent(ua == null ? null : truncate(ua, 500));
        }
        auditEventRepository.save(event);

        if (SCOPE_RESOURCES.contains(resource)) evictScopes(event.getOrganizationId());

        String webhookEvent = WebhookService.eventFor(action, resource);
        if (webhookEvent != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", resourceId);
            data.put("name", resourceName);
            data.put("actorId", event.getUserId());
            data.put("record", "delete".equals(action) ? before : after);
            if ("update".equals(action) && before != null) data.put("previous", before);
            webhookService.publish(event.getOrganizationId(), webhookEvent, data);
        }
    }

    /** Evict now and again after commit, so a concurrent read can't re-cache the pre-commit state. */
    private void evictScopes(Long organizationId) {
        scopeCache.evictOrganization(organizationId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    scopeCache.evictOrganization(organizationId);
                }
            });
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.debug("audit snapshot serialization failed: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
