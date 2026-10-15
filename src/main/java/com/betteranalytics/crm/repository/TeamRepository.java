package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByOrganizationId(Long organizationId);
    Optional<Team> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Teams a given user leads — the roster half of TEAM scope. */
    List<Team> findByOrganizationIdAndLeaderId(Long organizationId, Long leaderId);

    /** Used to refuse deleting a department that teams still point at. */
    boolean existsByOrganizationIdAndDepartmentId(Long organizationId, Long departmentId);
}
