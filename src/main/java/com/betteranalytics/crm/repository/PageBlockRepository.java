package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.PageBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface PageBlockRepository extends JpaRepository<PageBlock, Long> {
    List<PageBlock> findByPageIdOrderByPosition(Long pageId);

    /** Stable order: position, then id (two blocks can briefly share a position). */
    List<PageBlock> findByPageIdOrderByPositionAscIdAsc(Long pageId);

    Optional<PageBlock> findByIdAndPageId(Long id, Long pageId);

    long countByPageId(Long pageId);

    @Query("SELECT COALESCE(MAX(b.position), -1) FROM PageBlock b WHERE b.pageId = :pageId")
    int maxPosition(@Param("pageId") Long pageId);

    /** Opens a gap after {@code position} so a block can be inserted in the middle. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PageBlock b SET b.position = b.position + 1 WHERE b.pageId = :pageId AND b.position > :position")
    int shiftAfter(@Param("pageId") Long pageId, @Param("position") int position);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PageBlock b WHERE b.pageId = :pageId")
    int deleteAllForPage(@Param("pageId") Long pageId);

    void deleteByPageId(Long pageId);
}
