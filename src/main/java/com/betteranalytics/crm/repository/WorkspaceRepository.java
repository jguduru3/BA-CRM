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
    Optional<Workspace> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<Workspace> findByOrganizationIdAndIsDefaultTrue(Long organizationId);

    /** Row-locks every workspace of the org — serialises concurrent "set default" changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Workspace w WHERE w.organizationId = :orgId")
    List<Workspace> lockAllByOrganizationId(@Param("orgId") Long orgId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Workspace w SET w.isDefault = false WHERE w.organizationId = :orgId AND w.id <> :keepId AND w.isDefault = true")
    int clearOtherDefaults(@Param("orgId") Long orgId, @Param("keepId") Long keepId);
}
