package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long>, JpaSpecificationExecutor<Contact> {
    List<Contact> findByOrganizationId(Long organizationId);

    Optional<Contact> findByIdAndOrganizationId(Long id, Long organizationId);

    // NEW — soft-delete trash/restore support. These deliberately use
    // nativeQuery=true: the entity's @SQLRestriction("deleted_at IS NULL")
    // hides soft-deleted rows from every normal (JPQL/derived) query, so a
    // native query is the one escape hatch for "actually let me see the
    // deleted ones" — restore, and a trash-listing view.
    @Query(value = "SELECT * FROM contacts WHERE organization_id = :orgId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<Contact> findTrashedByOrganizationId(@Param("orgId") Long orgId);

    @Query(value = "SELECT * FROM contacts WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<Contact> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);

    // ── V20+: CRM core ───────────────────────────────────────────────────────────

    Optional<Contact> findFirstByOrganizationIdAndEmailIgnoreCaseOrderByIdAsc(Long organizationId, String email);

    long countByOrganizationIdAndCompanyId(Long organizationId, Long companyId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Contact c SET c.deletedAt = :now, c.deletedBy = :by, c.deleteReason = :reason " +
           "WHERE c.organizationId = :orgId AND c.companyId = :companyId AND c.deletedAt IS NULL")
    int softDeleteByCompany(@Param("orgId") Long orgId, @Param("companyId") Long companyId,
                            @Param("now") Instant now, @Param("by") Long by, @Param("reason") String reason);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE contacts SET deleted_at = NULL, deleted_by = NULL, delete_reason = NULL " +
                   "WHERE organization_id = :orgId AND company_id = :companyId AND delete_reason = :reason", nativeQuery = true)
    int restoreByCompany(@Param("orgId") Long orgId, @Param("companyId") Long companyId, @Param("reason") String reason);
}
