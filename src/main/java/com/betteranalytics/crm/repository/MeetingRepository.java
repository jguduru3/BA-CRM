package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Meeting;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Meeting> {
    List<Meeting> findByOrganizationIdOrderByStartAtDesc(Long organizationId);
    List<Meeting> findByOrganizationIdOrderByStartAtDesc(Long organizationId, Pageable pageable);
    List<Meeting> findByOrganizationIdAndOrganizerIdOrderByStartAtDesc(Long organizationId, Long organizerId, Pageable pageable);
    List<Meeting> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByStartAtDesc(
            Long organizationId, String relatedType, Long relatedId, Pageable pageable);
    List<Meeting> findByOrganizationIdAndRelatedTypeAndRelatedIdAndOrganizerIdOrderByStartAtDesc(
            Long organizationId, String relatedType, Long relatedId, Long organizerId, Pageable pageable);
    List<Meeting> findByOrganizationIdAndOrganizerIdOrderByStartAtDesc(Long organizationId, Long organizerId);
    List<Meeting> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByStartAtDesc(
            Long organizationId, String relatedType, Long relatedId);
    Optional<Meeting> findByIdAndOrganizationId(Long id, Long organizationId);

    @Query(value = "SELECT * FROM meetings WHERE organization_id = :orgId AND id = :id", nativeQuery = true)
    Optional<Meeting> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);
}
