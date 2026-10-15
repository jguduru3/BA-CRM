package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.DatabaseRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DatabaseRecordRepository extends JpaRepository<DatabaseRecord, Long> {
    List<DatabaseRecord> findByDatabaseIdAndOrganizationId(Long databaseId, Long organizationId);
    List<DatabaseRecord> findByDatabaseIdAndOrganizationId(Long databaseId, Long organizationId, Pageable pageable);
    Optional<DatabaseRecord> findByIdAndOrganizationId(Long id, Long organizationId);
    @Query(value = "SELECT * FROM database_records WHERE database_id = :dbId AND organization_id = :orgId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", nativeQuery = true)
    List<DatabaseRecord> findTrashedByDatabaseIdAndOrganizationId(@Param("dbId") Long dbId, @Param("orgId") Long orgId);
    @Query(value = "SELECT * FROM database_records WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<DatabaseRecord> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);

    /** Hard-deletes every record (live and soft-deleted) of a database — only used when the database itself is deleted. */
    @Modifying
    @Query(value = "DELETE FROM database_records WHERE database_id = :dbId AND organization_id = :orgId", nativeQuery = true)
    int hardDeleteAllForDatabase(@Param("dbId") Long dbId, @Param("orgId") Long orgId);
}
