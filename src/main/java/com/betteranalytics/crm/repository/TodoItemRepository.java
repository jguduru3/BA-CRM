package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.TodoItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TodoItemRepository extends JpaRepository<TodoItem, Long> {
    List<TodoItem> findByListIdOrderByPositionAscCreatedAtDesc(Long listId);
    List<TodoItem> findByListIdAndParentIdIsNullOrderByPositionAsc(Long listId);
    List<TodoItem> findByParentIdOrderByPositionAsc(Long parentId);

    Optional<TodoItem> findByIdAndOrganizationId(Long id, Long orgId);

    @Query("SELECT t FROM TodoItem t WHERE t.organizationId = :orgId " +
           "AND t.isDone = false AND t.dueAt IS NOT NULL AND t.dueAt <= :before")
    List<TodoItem> findOverdue(@Param("orgId") Long orgId, @Param("before") Instant before);
}
