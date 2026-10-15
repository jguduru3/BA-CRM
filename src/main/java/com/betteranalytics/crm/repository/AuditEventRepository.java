package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>,
                                              JpaSpecificationExecutor<AuditEvent> {
    // JpaSpecificationExecutor lets AuditEventController combine any subset of
    // its optional filters (resource/user/action/date range) in one query.
    List<AuditEvent> findByOrganizationId(Long organizationId);

    List<AuditEvent> findByOrganizationIdAndResource(Long organizationId, String resource);

    List<AuditEvent> findByOrganizationIdAndUserId(Long organizationId, Long userId);

    List<AuditEvent> findByOrganizationIdAndAction(Long organizationId, String action);

    // Filter facets for the audit-log screen (AuditEventController#facets).
    @Query("select distinct e.resource from AuditEvent e where e.organizationId = :org order by e.resource")
    List<String> distinctResources(@Param("org") Long organizationId);

    @Query("select distinct e.action from AuditEvent e where e.organizationId = :org order by e.action")
    List<String> distinctActions(@Param("org") Long organizationId);

    @Query("select distinct e.resource from AuditEvent e where e.organizationId = :org and e.userId in :users order by e.resource")
    List<String> distinctResourcesForUsers(@Param("org") Long organizationId, @Param("users") Collection<Long> userIds);

    @Query("select distinct e.action from AuditEvent e where e.organizationId = :org and e.userId in :users order by e.action")
    List<String> distinctActionsForUsers(@Param("org") Long organizationId, @Param("users") Collection<Long> userIds);
}
