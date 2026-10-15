package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.AiAutomation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiAutomationRepository extends JpaRepository<AiAutomation, Long> {
    List<AiAutomation> findByOrganizationIdOrderByCreatedAtDesc(Long orgId);
    Optional<AiAutomation> findByIdAndOrganizationId(Long id, Long orgId);
    List<AiAutomation> findByOrganizationIdAndTriggerTypeAndIsActiveTrue(Long orgId, String triggerType);
    List<AiAutomation> findByTriggerTypeAndIsActiveTrue(String triggerType);

    /** Atomic run counter — no lost updates when runs overlap. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AiAutomation a SET a.runCount = a.runCount + 1, a.lastRunAt = :now WHERE a.id = :id")
    int recordRun(@Param("id") Long id, @Param("now") Instant now);
}
