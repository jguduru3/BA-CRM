package com.betteranalytics.crm.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RoutingSettingsRepository extends JpaRepository<RoutingSettings, Long> {

    Optional<RoutingSettings> findByOrganizationId(Long organizationId);

    /**
     * Moves the rotation pointer only if nobody moved it since we read {@code expectedVersion}
     * (compare-and-set). Returns 1 on success; 0 means a concurrent assignment won — re-read and retry.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoutingSettings r SET r.lastAssignedUserId = :userId, r.version = r.version + 1 " +
           "WHERE r.organizationId = :orgId AND r.version = :expectedVersion")
    int advance(@Param("orgId") Long orgId, @Param("expectedVersion") long expectedVersion,
                @Param("userId") Long userId);

    /** Settings save: everything but the pointer; bumps the version so in-flight rotations re-read. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RoutingSettings r SET r.enabled = :enabled, r.teamId = :teamId, " +
           "r.respectTerritory = :respectTerritory, r.updatedBy = :by, r.updatedAt = :now, " +
           "r.version = r.version + 1 WHERE r.organizationId = :orgId")
    int updateSettings(@Param("orgId") Long orgId, @Param("enabled") boolean enabled, @Param("teamId") Long teamId,
                       @Param("respectTerritory") boolean respectTerritory, @Param("by") Long by,
                       @Param("now") java.time.Instant now);
}
