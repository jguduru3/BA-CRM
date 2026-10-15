package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WhiteboardItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WhiteboardItemRepository extends JpaRepository<WhiteboardItem, Long> {
    List<WhiteboardItem> findByBoardIdAndOrganizationId(Long boardId, Long organizationId);
    Optional<WhiteboardItem> findByIdAndOrganizationId(Long id, Long organizationId);
    List<WhiteboardItem> findByBoardIdAndOrganizationIdAndIdIn(Long boardId, Long organizationId, Collection<Long> ids);

    /** Detaches items from an attachment that is about to be deleted (the FK has no ON DELETE action). */
    @Modifying
    @Transactional
    @Query("UPDATE WhiteboardItem i SET i.attachmentId = NULL WHERE i.organizationId = :orgId AND i.attachmentId = :attachmentId")
    int clearAttachmentReferences(@Param("orgId") Long orgId, @Param("attachmentId") Long attachmentId);
}
