package com.betteranalytics.crm.sequences;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SequenceEnrollmentRepository extends JpaRepository<SequenceEnrollment, Long> {

    Optional<SequenceEnrollment> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Row lock for every state transition (PostgreSQL and H2 both honour SELECT … FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SequenceEnrollment e WHERE e.id = :id")
    Optional<SequenceEnrollment> lockById(@Param("id") Long id);

    List<SequenceEnrollment> findBySequenceIdAndOrganizationIdOrderByCreatedAtDesc(Long sequenceId, Long orgId, Pageable page);

    List<SequenceEnrollment> findBySequenceIdAndOrganizationIdAndStatusOrderByCreatedAtDesc(
            Long sequenceId, Long orgId, String status, Pageable page);

    List<SequenceEnrollment> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
            Long orgId, String relatedType, Long relatedId);

    List<SequenceEnrollment> findBySequenceIdAndStatusIn(Long sequenceId, Collection<String> statuses);

    List<SequenceEnrollment> findByOrganizationIdAndContactPhoneAndStatusIn(
            Long orgId, String contactPhone, Collection<String> statuses);

    List<SequenceEnrollment> findByOrganizationIdAndContactEmailAndStatusIn(
            Long orgId, String contactEmail, Collection<String> statuses);

    @Query("SELECT e.sequenceId, e.status, COUNT(e) FROM SequenceEnrollment e " +
           "WHERE e.organizationId = :orgId GROUP BY e.sequenceId, e.status")
    List<Object[]> countBySequenceAndStatus(@Param("orgId") Long orgId);

    /** Due, unclaimed (or stale-claimed) enrollments of active sequences — H2 / non-PostgreSQL claim path. */
    @Query("SELECT e.id FROM SequenceEnrollment e WHERE e.status = 'ACTIVE' AND e.nextRunAt <= :now " +
           "AND (e.claimedAt IS NULL OR e.claimedAt < :staleBefore) " +
           "AND e.sequenceId IN (SELECT s.id FROM Sequence s WHERE s.active = true) ORDER BY e.nextRunAt, e.id")
    List<Long> findDueIds(@Param("now") Instant now, @Param("staleBefore") Instant staleBefore, Pageable page);

    /** Atomic conditional claim: exactly one concurrent caller gets 1. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SequenceEnrollment e SET e.claimedAt = :now WHERE e.id = :id AND e.status = 'ACTIVE' " +
           "AND e.nextRunAt <= :now AND (e.claimedAt IS NULL OR e.claimedAt < :staleBefore)")
    int claim(@Param("id") Long id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
