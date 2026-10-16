package com.betteranalytics.crm.sequences;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SequenceStepExecutionRepository extends JpaRepository<SequenceStepExecution, Long> {
    Optional<SequenceStepExecution> findByEnrollmentIdAndStepIndex(Long enrollmentId, int stepIndex);

    List<SequenceStepExecution> findByEnrollmentIdOrderByStepIndexAsc(Long enrollmentId);

    Optional<SequenceStepExecution> findFirstByOrganizationIdAndWaMessageId(Long orgId, String waMessageId);

    long countByEnrollmentId(Long enrollmentId);
}
