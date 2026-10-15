package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.DatabaseProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DatabasePropertyRepository extends JpaRepository<DatabaseProperty, Long> {
    List<DatabaseProperty> findByDatabaseIdAndOrganizationIdOrderByPosition(Long databaseId, Long organizationId);
    Optional<DatabaseProperty> findByIdAndOrganizationId(Long id, Long organizationId);
    void deleteByDatabaseIdAndOrganizationId(Long databaseId, Long organizationId);

    @Modifying
    @Query("DELETE FROM DatabaseProperty p WHERE p.databaseId = :dbId AND p.organizationId = :orgId")
    int deleteAllForDatabase(@Param("dbId") Long dbId, @Param("orgId") Long orgId);

    /** Other databases' relation properties pointing at a database that is being deleted (FK has no ON DELETE action). */
    @Modifying
    @Query("UPDATE DatabaseProperty p SET p.relationDbId = NULL WHERE p.relationDbId = :dbId AND p.organizationId = :orgId")
    int clearRelationReferences(@Param("dbId") Long dbId, @Param("orgId") Long orgId);
}
