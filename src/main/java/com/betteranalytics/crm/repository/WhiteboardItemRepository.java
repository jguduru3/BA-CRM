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

    /** All items of a board in one query, drawing order (connectors and z-index ties stay stable). */
    @Query("SELECT i FROM WhiteboardItem i WHERE i.boardId = :boardId AND i.organizationId = :orgId ORDER BY i.zIndex ASC, i.id ASC")
    List<WhiteboardItem> findBoardItems(@Param("boardId") Long boardId, @Param("orgId") Long orgId);

    Optional<WhiteboardItem> findByIdAndOrganizationId(Long id, Long organizationId);
    List<WhiteboardItem> findByBoardIdAndOrganizationIdAndIdIn(Long boardId, Long organizationId, Collection<Long> ids);

    /** Connectors attached to an element — deleted together with it. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WhiteboardItem i WHERE i.organizationId = :orgId AND (i.fromItemId = :itemId OR i.toItemId = :itemId)")
    int deleteConnectorsOf(@Param("orgId") Long orgId, @Param("itemId") Long itemId);

    /** Board-wide delete: connectors first so self-references never block the item delete. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WhiteboardItem i WHERE i.boardId = :boardId AND i.organizationId = :orgId AND i.type = 'connector'")
    int deleteConnectorsOfBoard(@Param("boardId") Long boardId, @Param("orgId") Long orgId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM WhiteboardItem i WHERE i.boardId = :boardId AND i.organizationId = :orgId")
    int deleteAllOfBoard(@Param("boardId") Long boardId, @Param("orgId") Long orgId);

    @Query("SELECT i.boardId, COUNT(i) FROM WhiteboardItem i WHERE i.organizationId = :orgId AND i.boardId IN :boardIds GROUP BY i.boardId")
    List<Object[]> countByBoard(@Param("orgId") Long orgId, @Param("boardIds") Collection<Long> boardIds);

    /** Detaches items from an attachment that is about to be deleted (the FK has no ON DELETE action). */
    @Modifying
    @Transactional
    @Query("UPDATE WhiteboardItem i SET i.attachmentId = NULL WHERE i.organizationId = :orgId AND i.attachmentId = :attachmentId")
    int clearAttachmentReferences(@Param("orgId") Long orgId, @Param("attachmentId") Long attachmentId);
}
