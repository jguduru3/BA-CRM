package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long>, JpaSpecificationExecutor<Project> {
    List<Project> findByOrganizationIdOrderByCreatedAtDesc(Long orgId);
    Optional<Project> findByIdAndOrganizationId(Long id, Long orgId);

    @Query(value = "SELECT * FROM projects WHERE organization_id = :orgId " +
                   "AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<Project> findTrashedByOrganizationId(@Param("orgId") Long orgId);

    @Query(value = "SELECT * FROM projects WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<Project> findByIdAndOrganizationIdIncludingDeleted(
            @Param("id") Long id, @Param("orgId") Long orgId);

    /** Task counts per project in one grouped query: [projectId, total, done]. */
    @Query(value = "SELECT project_id, COUNT(*), SUM(CASE WHEN status = 'Done' THEN 1 ELSE 0 END) FROM tasks " +
                   "WHERE organization_id = :orgId AND deleted_at IS NULL AND project_id IN (:ids) GROUP BY project_id",
           nativeQuery = true)
    List<Object[]> taskCountsByProject(@Param("orgId") Long orgId, @Param("ids") Collection<Long> ids);

    /** Tasks keep living when their project is deleted — they just lose the link. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE tasks SET project_id = NULL WHERE organization_id = :orgId AND project_id = :projectId",
           nativeQuery = true)
    int unlinkTasks(@Param("orgId") Long orgId, @Param("projectId") Long projectId);
}
