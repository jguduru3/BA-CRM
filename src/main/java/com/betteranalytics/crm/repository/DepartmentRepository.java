package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {
    List<Department> findByOrganizationId(Long organizationId);
    List<Department> findByOrganizationIdAndParentId(Long organizationId, Long parentId);
    Optional<Department> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Used to refuse deleting a department that still has child departments. */
    boolean existsByOrganizationIdAndParentId(Long organizationId, Long parentId);
}
