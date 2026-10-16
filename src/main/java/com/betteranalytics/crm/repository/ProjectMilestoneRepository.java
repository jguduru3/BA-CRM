package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.ProjectMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectMilestoneRepository extends JpaRepository<ProjectMilestone, Long> {
    List<ProjectMilestone> findByProjectIdOrderByPosition(Long projectId);
    Optional<ProjectMilestone> findByIdAndOrganizationId(Long id, Long orgId);
    void deleteByProjectId(Long projectId);

    /** Milestone progress for many projects in one grouped query: [projectId, total, completed]. */
    @Query("SELECT m.projectId, COUNT(m), SUM(CASE WHEN m.isCompleted = true THEN 1 ELSE 0 END) " +
           "FROM ProjectMilestone m WHERE m.organizationId = :orgId AND m.projectId IN :ids GROUP BY m.projectId")
    List<Object[]> progressByProject(@Param("orgId") Long orgId, @Param("ids") Collection<Long> ids);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ProjectMilestone m WHERE m.projectId = :projectId AND m.organizationId = :orgId")
    int deleteAllForProject(@Param("projectId") Long projectId, @Param("orgId") Long orgId);
}
