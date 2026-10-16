package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {
    List<Lead> findByOrganizationId(Long organizationId);

    Optional<Lead> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Oldest live lead with this lowercase email (inbound email → record). */
    Optional<Lead> findFirstByOrganizationIdAndEmailNormalizedOrderByIdAsc(Long organizationId, String emailNormalized);

    @Query("""
        SELECT l FROM Lead l
        WHERE l.organizationId = :orgId
        AND (:search IS NULL OR :search = '' OR LOWER(l.name) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(l.email) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(l.businessName) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY l.updatedAt DESC
        """)
    List<Lead> search(@Param("orgId") Long orgId, @Param("search") String search);

    // NEW — soft-delete trash/restore support. These deliberately use
    // nativeQuery=true: the entity's @SQLRestriction("deleted_at IS NULL")
    // hides soft-deleted rows from every normal (JPQL/derived) query, so a
    // native query is the one escape hatch for "actually let me see the
    // deleted ones" — restore, and a trash-listing view.
    @Query(value = "SELECT * FROM leads WHERE organization_id = :orgId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<Lead> findTrashedByOrganizationId(@Param("orgId") Long orgId);

    @Query(value = "SELECT * FROM leads WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<Lead> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);

    // ── V20+: CRM core ───────────────────────────────────────────────────────────

    /**
     * Atomic claim from the unassigned pool: succeeds (returns 1) only while the
     * lead is still unowned, so two reps racing for the same lead can't both win.
     * Bumps the version so in-flight edits of the old state get 409 STALE_UPDATE.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Lead l SET l.ownerId = :userId, l.updatedAt = :now, l.version = l.version + 1 " +
           "WHERE l.id = :id AND l.organizationId = :orgId AND l.ownerId IS NULL AND l.deletedAt IS NULL")
    int claimIfUnowned(@Param("id") Long id, @Param("orgId") Long orgId,
                       @Param("userId") Long userId, @Param("now") Instant now);

    /** Row lock (SELECT ... FOR UPDATE) — serialises concurrent conversions of one lead. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Lead l WHERE l.id = :id AND l.organizationId = :orgId")
    Optional<Lead> lockByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    /** Owned, open leads whose follow-up date has passed and whose owner hasn't been told yet. */
    @Query("SELECT l FROM Lead l WHERE l.followUpAt < :now AND l.followUpNotifiedAt IS NULL " +
           "AND l.ownerId IS NOT NULL AND l.convertedOppId IS NULL AND l.stage NOT IN ('Won', 'Lost') " +
           "ORDER BY l.followUpAt ASC")
    List<Lead> findOverdueFollowUps(@Param("now") Instant now, Pageable pageable);

    /**
     * Marks the overdue notice as sent — only if nobody sent it meanwhile and the date
     * is still the one we're notifying about. Deliberately does NOT bump the version:
     * a background bookkeeping flag must not make a user's in-flight edit fail.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Lead l SET l.followUpNotifiedAt = :now " +
           "WHERE l.id = :id AND l.followUpNotifiedAt IS NULL AND l.followUpAt = :followUpAt")
    int markFollowUpNotified(@Param("id") Long id, @Param("followUpAt") Instant followUpAt, @Param("now") Instant now);

    long countByOrganizationIdAndCompanyId(Long organizationId, Long companyId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Lead l SET l.deletedAt = :now, l.deletedBy = :by, l.deleteReason = :reason " +
           "WHERE l.organizationId = :orgId AND l.companyId = :companyId AND l.deletedAt IS NULL")
    int softDeleteByCompany(@Param("orgId") Long orgId, @Param("companyId") Long companyId,
                            @Param("now") Instant now, @Param("by") Long by, @Param("reason") String reason);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE leads SET deleted_at = NULL, deleted_by = NULL, delete_reason = NULL " +
                   "WHERE organization_id = :orgId AND company_id = :companyId AND delete_reason = :reason", nativeQuery = true)
    int restoreByCompany(@Param("orgId") Long orgId, @Param("companyId") Long companyId, @Param("reason") String reason);
}
