package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.AiAutomationRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiAutomationRunRepository extends JpaRepository<AiAutomationRun, Long> {
    List<AiAutomationRun> findByAutomationIdOrderByStartedAtDesc(Long automationId);
    List<AiAutomationRun> findByAutomationIdAndOrganizationIdOrderByStartedAtDesc(Long automationId, Long orgId, Pageable pageable);
    List<AiAutomationRun> findByOrganizationIdOrderByStartedAtDesc(Long orgId);
}
