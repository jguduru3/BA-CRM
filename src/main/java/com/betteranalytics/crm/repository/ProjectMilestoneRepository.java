package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.ProjectMilestone;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectMilestoneRepository extends JpaRepository<ProjectMilestone, Long> {
    List<ProjectMilestone> findByProjectIdOrderByPosition(Long projectId);
    Optional<ProjectMilestone> findByIdAndOrganizationId(Long id, Long orgId);
    void deleteByProjectId(Long projectId);
}
