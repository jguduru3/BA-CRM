package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.DatabaseView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DatabaseViewRepository extends JpaRepository<DatabaseView, Long> {
    List<DatabaseView> findByDatabaseIdAndOrganizationIdOrderByPosition(Long databaseId, Long organizationId);
    Optional<DatabaseView> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<DatabaseView> findByDatabaseIdAndOrganizationIdAndIsDefaultTrue(Long databaseId, Long organizationId);

    @Modifying
    @Query("DELETE FROM DatabaseView v WHERE v.databaseId = :dbId AND v.organizationId = :orgId")
    int deleteAllForDatabase(@Param("dbId") Long dbId, @Param("orgId") Long orgId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DatabaseView v SET v.isDefault = false WHERE v.databaseId = :dbId AND v.organizationId = :orgId AND v.id <> :keepId AND v.isDefault = true")
    int clearOtherDefaults(@Param("dbId") Long dbId, @Param("orgId") Long orgId, @Param("keepId") Long keepId);
}
