package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.TodoList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface TodoListRepository extends JpaRepository<TodoList, Long> {
    @Query("SELECT l FROM TodoList l WHERE l.organizationId = :orgId " +
           "AND (l.ownerId = :userId OR l.isShared = true) ORDER BY l.position ASC, l.createdAt DESC")
    List<TodoList> findVisibleByOrgAndUser(@Param("orgId") Long orgId, @Param("userId") Long userId);

    Optional<TodoList> findByIdAndOrganizationId(Long id, Long orgId);
}
