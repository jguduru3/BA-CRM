package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.PropertyValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PropertyValueRepository extends JpaRepository<PropertyValue, Long> {
    List<PropertyValue> findByRecordIdAndOrganizationId(Long recordId, Long organizationId);
    /** Batch load for a page of records — avoids one query per record. */
    List<PropertyValue> findByRecordIdInAndOrganizationId(Collection<Long> recordIds, Long organizationId);
    Optional<PropertyValue> findByRecordIdAndPropertyIdAndOrganizationId(Long recordId, Long propertyId, Long organizationId);
    @Modifying
    @Query("DELETE FROM PropertyValue pv WHERE pv.recordId = :recordId AND pv.organizationId = :orgId")
    void deleteByRecordIdAndOrganizationId(@Param("recordId") Long recordId, @Param("orgId") Long orgId);

    @Modifying
    @Query("DELETE FROM PropertyValue pv WHERE pv.propertyId = :propertyId AND pv.organizationId = :orgId")
    int deleteByPropertyIdAndOrganizationId(@Param("propertyId") Long propertyId, @Param("orgId") Long orgId);

    /** All values of every record (including soft-deleted ones) of a database. */
    @Modifying
    @Query(value = "DELETE FROM property_values WHERE organization_id = :orgId AND record_id IN " +
                   "(SELECT id FROM database_records WHERE database_id = :dbId AND organization_id = :orgId)",
           nativeQuery = true)
    int deleteAllForDatabase(@Param("dbId") Long dbId, @Param("orgId") Long orgId);
}
