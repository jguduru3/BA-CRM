package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Delegation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    List<Delegation> findByOrganizationId(Long organizationId);

    Optional<Delegation> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * Active delegations where actor is the delegate right now.
     * Used by PermissionService.isInTeamScope() to check inherited access.
     */
    @Query("""
        SELECT d FROM Delegation d
        WHERE d.delegateId = :delegateId
          AND d.startDate <= :today
          AND d.endDate   >= :today
        """)
    List<Delegation> findActiveDelegationsByDelegate(
            @Param("delegateId") Long delegateId,
            @Param("today") LocalDate today);

    /**
     * All delegations created by a specific delegator (for their management view).
     */
    List<Delegation> findByDelegatorId(Long delegatorId);

    /** Current or future delegations restricted to a team — blocks deleting that team. */
    boolean existsByOrganizationIdAndTeamIdAndEndDateGreaterThanEqual(
            Long organizationId, Long teamId, LocalDate date);
}
