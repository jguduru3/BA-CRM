package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.AiJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AiJobRepository extends JpaRepository<AiJob, Long> {

    List<AiJob> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    List<AiJob> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable pageable);

    List<AiJob> findByOrganizationIdAndCreatedByOrderByCreatedAtDesc(Long organizationId, Long createdBy, Pageable pageable);

    List<AiJob> findByOrganizationIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
            Long organizationId, String entityType, Long entityId);

    List<AiJob> findByOrganizationIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
            Long organizationId, String entityType, Long entityId, Pageable pageable);

    Optional<AiJob> findByIdAndOrganizationId(Long id, Long organizationId);
}
