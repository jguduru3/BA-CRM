package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long>, JpaSpecificationExecutor<Company> {
    List<Company> findByOrganizationId(Long organizationId);

    Optional<Company> findByIdAndOrganizationId(Long id, Long organizationId);

    // NEW — soft-delete trash/restore support. These deliberately use
    // nativeQuery=true: the entity's @SQLRestriction("deleted_at IS NULL")
    // hides soft-deleted rows from every normal (JPQL/derived) query, so a
    // native query is the one escape hatch for "actually let me see the
    // deleted ones" — restore, and a trash-listing view.
    @Query(value = "SELECT * FROM companies WHERE organization_id = :orgId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<Company> findTrashedByOrganizationId(@Param("orgId") Long orgId);

    @Query(value = "SELECT * FROM companies WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<Company> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);

    // ── V20+: CRM core ───────────────────────────────────────────────────────────

    Optional<Company> findFirstByOrganizationIdAndNameIgnoreCaseOrderByIdAsc(Long organizationId, String name);
}
