package com.betteranalytics.crm.sequences;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SequenceStepRepository extends JpaRepository<SequenceStep, Long> {
    List<SequenceStep> findBySequenceIdOrderByStepIndexAsc(Long sequenceId);

    List<SequenceStep> findBySequenceIdInOrderByStepIndexAsc(Collection<Long> sequenceIds);

    long countBySequenceId(Long sequenceId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SequenceStep s WHERE s.sequenceId = :sequenceId")
    int deleteBySequenceId(@Param("sequenceId") Long sequenceId);
}
