package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Opportunity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long>, JpaSpecificationExecutor<Opportunity> {
    List<Opportunity> findByOrganizationId(Long organizationId);

    Optional<Opportunity> findByIdAndOrganizationId(Long id, Long organizationId);

    // NEW — soft-delete trash/restore support. These deliberately use
    // nativeQuery=true: the entity's @SQLRestriction("deleted_at IS NULL")
    // hides soft-deleted rows from every normal (JPQL/derived) query, so a
    // native query is the one escape hatch for "actually let me see the
    // deleted ones" — restore, and a trash-listing view.
    @Query(value = "SELECT * FROM opportunities WHERE organization_id = :orgId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<Opportunity> findTrashedByOrganizationId(@Param("orgId") Long orgId);

    @Query(value = "SELECT * FROM opportunities WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<Opportunity> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * Opportunities sourced from leads owned by a specific user.
     * Used by OpportunityController to give Inside Sales read-only visibility
     * into the stage of deals that came from their qualified leads (spec §5).
     */
    @Query("SELECT o FROM Opportunity o WHERE o.organizationId = :orgId AND o.sourcedByUserId = :userId")
    List<Opportunity> findByOrganizationIdAndSourcedByUserId(
            @Param("orgId") Long orgId, @Param("userId") Long userId);

    // ── V20+: CRM core ───────────────────────────────────────────────────────────

    /** Includes soft-deleted rows: the unique index on source_lead_id covers them too. */
    @Query(value = "SELECT * FROM opportunities WHERE source_lead_id = :leadId AND organization_id = :orgId", nativeQuery = true)
    Optional<Opportunity> findBySourceLeadIdIncludingDeleted(@Param("leadId") Long leadId, @Param("orgId") Long orgId);

    long countByOrganizationIdAndCompanyId(Long organizationId, Long companyId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Opportunity o SET o.deletedAt = :now, o.deletedBy = :by, o.deleteReason = :reason " +
           "WHERE o.organizationId = :orgId AND o.companyId = :companyId AND o.deletedAt IS NULL")
    int softDeleteByCompany(@Param("orgId") Long orgId, @Param("companyId") Long companyId,
                            @Param("now") Instant now, @Param("by") Long by, @Param("reason") String reason);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE opportunities SET deleted_at = NULL, deleted_by = NULL, delete_reason = NULL " +
                   "WHERE organization_id = :orgId AND company_id = :companyId AND delete_reason = :reason", nativeQuery = true)
    int restoreByCompany(@Param("orgId") Long orgId, @Param("companyId") Long companyId, @Param("reason") String reason);

    // ── V61+: pipeline intelligence (forecast categories, deal health) ──────────

    /** Keyset batch of open deals for the nightly health job. */
    @Query("SELECT o FROM Opportunity o WHERE o.stage NOT IN :closed AND o.id > :after ORDER BY o.id")
    List<Opportunity> findOpenAfter(@Param("closed") java.util.Collection<String> closed, @Param("after") long after,
                                    org.springframework.data.domain.Pageable page);

    /**
     * Stores a recomputed health score without bumping the optimistic-lock version
     * (a background recompute must not make an open edit form stale). Conditional on
     * the flags the caller read, so two concurrent recomputes notify only once.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Opportunity o SET o.healthScore = :score, o.healthFactors = :factors, o.healthFlags = :flags, " +
           "o.healthComputedAt = :at WHERE o.id = :id AND COALESCE(o.healthFlags, '') = :expectedFlags")
    int updateHealth(@Param("id") Long id, @Param("score") Integer score, @Param("factors") String factors,
                     @Param("flags") String flags, @Param("at") Instant at, @Param("expectedFlags") String expectedFlags);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Opportunity o SET o.healthScore = NULL, o.healthFactors = NULL, o.healthFlags = NULL " +
           "WHERE o.stage IN :closed AND (o.healthScore IS NOT NULL OR o.healthFlags IS NOT NULL)")
    int clearHealthOfClosed(@Param("closed") java.util.Collection<String> closed);

    @Query("SELECT o FROM Opportunity o WHERE o.forecastCategory IS NULL ORDER BY o.id")
    List<Opportunity> findWithoutForecastCategory(org.springframework.data.domain.Pageable page);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Opportunity o SET o.forecastCategory = :category, o.forecastCategoryOverridden = false " +
           "WHERE o.id = :id AND o.forecastCategory IS NULL")
    int fillForecastCategory(@Param("id") Long id, @Param("category") com.betteranalytics.crm.entity.ForecastCategory category);
}
