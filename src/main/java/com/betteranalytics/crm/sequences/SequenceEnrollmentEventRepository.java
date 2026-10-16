package com.betteranalytics.crm.sequences;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SequenceEnrollmentEventRepository extends JpaRepository<SequenceEnrollmentEvent, Long> {
    List<SequenceEnrollmentEvent> findByEnrollmentIdOrderByCreatedAtAscIdAsc(Long enrollmentId);
}
