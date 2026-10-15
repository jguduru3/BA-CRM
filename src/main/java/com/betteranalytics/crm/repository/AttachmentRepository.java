package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Attachment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    /** Attachments among the given ids inside one org (custom-database file properties). */
    List<Attachment> findByIdInAndOrganizationId(java.util.Collection<Long> ids, Long organizationId);

    List<Attachment> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
            Long organizationId, String relatedType, Long relatedId);
    List<Attachment> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
            Long organizationId, String relatedType, Long relatedId, Pageable pageable);
    Optional<Attachment> findByIdAndOrganizationId(Long id, Long organizationId);
}
