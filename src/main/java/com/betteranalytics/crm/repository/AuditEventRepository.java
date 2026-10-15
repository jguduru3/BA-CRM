package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>,
                                              JpaSpecificationExecutor<AuditEvent> {
    // JpaSpecificationExecutor lets AuditEventController combine any subset of
    // its optional filters (resource/user/action/date range) in one query.
    List<AuditEvent> findByOrganizationId(Long organizationId);

    List<AuditEvent> findByOrganizationIdAndResource(Long organizationId, String resource);

    List<AuditEvent> findByOrganizationIdAndUserId(Long organizationId, Long userId);

    List<AuditEvent> findByOrganizationIdAndAction(Long organizationId, String action);
}
