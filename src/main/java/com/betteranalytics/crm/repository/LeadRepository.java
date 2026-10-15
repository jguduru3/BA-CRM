package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface LeadRepository extends JpaRepository<Lead, Long> {
    List<Lead> findByOrganizationId(Long organizationId);

    Optional<Lead> findByIdAndOrganizationId(Long id, Long organizationId);

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
}
