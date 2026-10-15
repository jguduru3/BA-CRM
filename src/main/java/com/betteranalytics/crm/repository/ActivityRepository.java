package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findByOrganizationId(Long organizationId);

    /** Used by ActivityController GET /{id} and POST /{id}/correct. */
    Optional<Activity> findByIdAndOrganizationId(Long id, Long organizationId);

    /** All activities on a specific record (timeline view). */
    List<Activity> findByOrganizationIdAndRelatedTypeAndRelatedId(
            Long organizationId, String relatedType, Long relatedId);

    /** Correction entries linked to a specific original activity. */
    @Query("SELECT a FROM Activity a WHERE a.organizationId = :orgId AND a.correctionForId = :originalId")
    List<Activity> findCorrectionsFor(@Param("orgId") Long orgId, @Param("originalId") Long originalId);
}
