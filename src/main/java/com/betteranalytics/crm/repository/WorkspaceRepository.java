package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Workspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {
    List<Workspace> findByOrganizationId(Long organizationId);

    /** Default first, then alphabetical — the order a workspace switcher shows. */
    @Query("SELECT w FROM Workspace w WHERE w.organizationId = :orgId ORDER BY w.isDefault DESC, LOWER(w.name) ASC, w.id ASC")
    List<Workspace> findForSwitcher(@Param("orgId") Long orgId);

    Optional<Workspace> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<Workspace> findByOrganizationIdAndIsDefaultTrue(Long organizationId);
    long countByOrganizationId(Long organizationId);

    /** Row-locks every workspace of the org — serialises concurrent default changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Workspace w WHERE w.organizationId = :orgId")
    List<Workspace> lockAllByOrganizationId(@Param("orgId") Long orgId);

    /** Demotes every default except keepId — clears the DB-enforced mirror column too. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Workspace w SET w.isDefault = false, w.defaultOrgId = NULL " +
           "WHERE w.organizationId = :orgId AND w.id <> :keepId AND w.isDefault = true")
    int clearOtherDefaults(@Param("orgId") Long orgId, @Param("keepId") Long keepId);

    /** Teams still pointing at a workspace (teams.workspace_id has no ON DELETE action). */
    @Query(value = "SELECT COUNT(*) FROM teams WHERE workspace_id = :id", nativeQuery = true)
    long countTeamsInWorkspace(@Param("id") Long id);
}
