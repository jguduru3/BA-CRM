package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    List<ProjectMember> findByProjectId(Long projectId);
    Optional<ProjectMember> findByProjectIdAndUserId(Long projectId, Long userId);
    void deleteByProjectIdAndUserId(Long projectId, Long userId);

    /** Member counts for many projects in one grouped query: [projectId, count]. */
    @Query("SELECT m.projectId, COUNT(m) FROM ProjectMember m WHERE m.organizationId = :orgId " +
           "AND m.projectId IN :ids GROUP BY m.projectId")
    List<Object[]> countByProject(@Param("orgId") Long orgId, @Param("ids") Collection<Long> ids);

    /** Members of many projects in one query (avatars on the project cards). */
    List<ProjectMember> findByOrganizationIdAndProjectIdIn(Long organizationId, Collection<Long> projectIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ProjectMember m WHERE m.projectId = :projectId AND m.organizationId = :orgId")
    int deleteAllForProject(@Param("projectId") Long projectId, @Param("orgId") Long orgId);
}
