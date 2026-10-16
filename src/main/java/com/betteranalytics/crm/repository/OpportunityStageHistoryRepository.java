package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.OpportunityStageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OpportunityStageHistoryRepository extends JpaRepository<OpportunityStageHistory, Long> {

    List<OpportunityStageHistory> findByOpportunityIdAndOrganizationIdOrderByEnteredAtAscIdAsc(Long opportunityId, Long organizationId);

    Optional<OpportunityStageHistory> findFirstByOpportunityIdOrderByEnteredAtDescIdDesc(Long opportunityId);

    /**
     * Org benchmark: median days a deal spends in each stage, over completed stints
     * (rows that ended a stint), for stages with at least {@code minSamples} samples.
     * PERCENTILE_CONT ... WITHIN GROUP runs on both PostgreSQL and H2.
     */
    @Query(value = "SELECT from_stage, PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY seconds_in_from_stage) / 86400.0, COUNT(*) " +
                   "FROM opportunity_stage_history " +
                   "WHERE organization_id = :orgId AND from_stage IS NOT NULL AND seconds_in_from_stage IS NOT NULL " +
                   "GROUP BY from_stage HAVING COUNT(*) >= :minSamples", nativeQuery = true)
    List<Object[]> medianDaysByStage(@Param("orgId") Long orgId, @Param("minSamples") long minSamples);

    /**
     * Safety net for deals created by a path that did not write history: one
     * initial row (current stage, entered when the deal was created).
     */
    @Modifying
    @Query(value = "INSERT INTO opportunity_stage_history (opportunity_id, organization_id, from_stage, to_stage, changed_by, entered_at) " +
                   "SELECT o.id, o.organization_id, NULL, o.stage, o.owner_id, COALESCE(o.stage_entered_at, o.created_at, o.updated_at, CURRENT_TIMESTAMP) " +
                   "FROM opportunities o WHERE NOT EXISTS (SELECT 1 FROM opportunity_stage_history h WHERE h.opportunity_id = o.id)",
           nativeQuery = true)
    int backfillMissing();
}
