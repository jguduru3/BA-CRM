package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.TodoItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TodoItemRepository extends JpaRepository<TodoItem, Long> {
    List<TodoItem> findByListIdOrderByPositionAscCreatedAtDesc(Long listId);
    List<TodoItem> findByListIdAndParentIdIsNullOrderByPositionAsc(Long listId);
    List<TodoItem> findByParentIdOrderByPositionAsc(Long parentId);

    /** Top-level items of a list, filtered/sorted/paged in the database. */
    @Query("SELECT t FROM TodoItem t WHERE t.listId = :listId AND t.parentId IS NULL " +
           "AND (:done IS NULL OR t.isDone = :done) ORDER BY t.isPinned DESC, t.position ASC, t.id ASC")
    List<TodoItem> findTopLevel(@Param("listId") Long listId, @Param("done") Boolean done, Pageable page);

    /** Sub-items of a set of parents in one query (no per-item lookups). */
    List<TodoItem> findByParentIdInOrderByPositionAsc(Collection<Long> parentIds);

    Optional<TodoItem> findByIdAndOrganizationId(Long id, Long orgId);

    @Query("SELECT t.id FROM TodoItem t WHERE t.parentId IN :parentIds")
    List<Long> findIdsByParentIdIn(@Param("parentIds") Collection<Long> parentIds);

    /** Detaches every item of a list from its parent so the list can be deleted in one statement. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TodoItem t SET t.parentId = NULL WHERE t.listId = :listId")
    int detachParentsInList(@Param("listId") Long listId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM TodoItem t WHERE t.id IN :ids")
    int deleteByIdIn(@Param("ids") Collection<Long> ids);

    @Query("SELECT t FROM TodoItem t WHERE t.organizationId = :orgId " +
           "AND t.isDone = false AND t.dueAt IS NOT NULL AND t.dueAt <= :before")
    List<TodoItem> findOverdue(@Param("orgId") Long orgId, @Param("before") Instant before);
}
