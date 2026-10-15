package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByOrganizationId(Long organizationId);
    List<Document> findByOrganizationIdOrderByUpdatedAtDesc(Long organizationId, Pageable pageable);
    List<Document> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByUpdatedAtDesc(
            Long organizationId, String relatedType, Long relatedId, Pageable pageable);
    Optional<Document> findByIdAndOrganizationId(Long id, Long organizationId);
    List<Document> findByOrganizationIdAndRelatedTypeAndRelatedId(Long organizationId, String relatedType, Long relatedId);

    @Query(value = "SELECT * FROM documents WHERE id = :id AND organization_id = :orgId", nativeQuery = true)
    Optional<Document> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);
}
