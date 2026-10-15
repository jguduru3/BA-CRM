package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByOrganizationId(Long organizationId);

    Optional<Task> findByIdAndOrganizationId(Long id, Long organizationId);

    // NEW — soft-delete trash/restore support. These deliberately use
    // nativeQuery=true: the entity's @SQLRestriction("deleted_at IS NULL")
    // hides soft-deleted rows from every normal (JPQL/derived) query, so a
    // native query is the one escape hatch for "actually let me see the
    // deleted ones" — restore, and a trash-listing view.
    @Query(value = "SELECT * FROM tasks WHERE organization_id = :orgId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<Task> findTrashedByOrganizationId(@Param("orgId") Long orgId);

    @Query(value = "SELECT * FROM tasks WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<Task> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);
}
