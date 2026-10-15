package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Campaign;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    List<Campaign> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
    List<Campaign> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable pageable);
    List<Campaign> findByOrganizationIdAndStatusOrderByCreatedAtDesc(Long organizationId, String status);
    List<Campaign> findByOrganizationIdAndStatusOrderByCreatedAtDesc(Long organizationId, String status, Pageable pageable);
    Optional<Campaign> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Scheduled campaigns whose start time has arrived (scheduler). */
    List<Campaign> findByStatusAndScheduledAtLessThanEqual(String status, Instant now);

    /** Campaigns in a status for a channel (scheduler resumes interrupted dispatches). */
    List<Campaign> findByStatusAndType(String status, String type);

    @Query("SELECT c.status FROM Campaign c WHERE c.id = :id")
    Optional<String> findStatusById(@Param("id") Long id);

    /** Counter refresh as a targeted UPDATE so it never overwrites a concurrent status change. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.sentCount = :sent, c.deliveredCount = :delivered, c.readCount = :read, " +
           "c.failedCount = :failed, c.updatedAt = :now WHERE c.id = :id")
    int updateCounts(@Param("id") Long id, @Param("sent") int sent, @Param("delivered") int delivered,
                     @Param("read") int read, @Param("failed") int failed, @Param("now") Instant now);

    /** scheduled → running, only if still scheduled (scheduler; safe against concurrent cancel). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.status = 'running', c.startedAt = COALESCE(c.startedAt, :now), c.updatedAt = :now " +
           "WHERE c.id = :id AND c.status = 'scheduled'")
    int markScheduledRunning(@Param("id") Long id, @Param("now") Instant now);

    /** running → completed, only if still running. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Campaign c SET c.status = 'completed', c.completedAt = :now, c.updatedAt = :now " +
           "WHERE c.id = :id AND c.status = 'running'")
    int markCompleted(@Param("id") Long id, @Param("now") Instant now);
}
