package com.betteranalytics.crm.sequences;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SequenceRepository extends JpaRepository<Sequence, Long> {
    List<Sequence> findByOrganizationIdOrderByNameAsc(Long organizationId);
    Optional<Sequence> findByIdAndOrganizationId(Long id, Long organizationId);
}
