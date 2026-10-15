package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByOrganizationIdOrderByCreatedAtDesc(Long orgId);
    Optional<Project> findByIdAndOrganizationId(Long id, Long orgId);

    @Query(value = "SELECT * FROM projects WHERE organization_id = :orgId " +
                   "AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<Project> findTrashedByOrganizationId(@Param("orgId") Long orgId);

    @Query(value = "SELECT * FROM projects WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<Project> findByIdAndOrganizationIdIncludingDeleted(
            @Param("id") Long id, @Param("orgId") Long orgId);
}
