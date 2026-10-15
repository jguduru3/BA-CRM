package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Note;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface NoteRepository extends JpaRepository<Note, Long> {
    List<Note> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
    List<Note> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable pageable);
    List<Note> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByPinnedDescCreatedAtDesc(
            Long organizationId, String relatedType, Long relatedId, Pageable pageable);
    List<Note> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByPinnedDescCreatedAtDesc(
            Long organizationId, String relatedType, Long relatedId);
    Optional<Note> findByIdAndOrganizationId(Long id, Long organizationId);

    @Query(value = "SELECT * FROM notes WHERE organization_id = :orgId AND id = :id", nativeQuery = true)
    Optional<Note> findByIdAndOrganizationIdIncludingDeleted(@Param("id") Long id, @Param("orgId") Long orgId);
}
