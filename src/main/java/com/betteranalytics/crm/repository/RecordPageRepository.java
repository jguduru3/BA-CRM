package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.RecordPage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RecordPageRepository extends JpaRepository<RecordPage, Long> {
    Optional<RecordPage> findByOrganizationIdAndRelatedTypeAndRelatedId(
            Long organizationId, String relatedType, Long relatedId);
    Optional<RecordPage> findByIdAndOrganizationId(Long id, Long organizationId);
}
