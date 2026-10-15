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

    List<AiAutomation> findByOrganizationIdOrderByCreatedAtDesc(Long orgId, org.springframework.data.domain.Pageable pageable);

    /** Cron claim for an automation that never fired — compare-and-set on NULL. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AiAutomation a SET a.scheduleLastFiredAt = :now WHERE a.id = :id AND a.scheduleLastFiredAt IS NULL")
    int claimFirstScheduledFire(@Param("id") Long id, @Param("now") Instant now);

    /** Cron claim — compare-and-set on the last fire time, so a slot fires at most once. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AiAutomation a SET a.scheduleLastFiredAt = :now WHERE a.id = :id AND a.scheduleLastFiredAt = :prev")
    int claimScheduledFire(@Param("id") Long id, @Param("prev") Instant prev, @Param("now") Instant now);
}
