package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "todo_items")
@Getter @Setter
public class TodoItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "list_id")
    private Long listId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "is_done", nullable = false)
    private boolean isDone = false;

    @Column(name = "is_pinned", nullable = false)
    private boolean isPinned = false;

    // low | normal | high | urgent
    @Column(nullable = false, length = 20)
    private String priority = "normal";

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(nullable = false)
    private int position = 0;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "related_type", length = 50)
    private String relatedType;

    @Column(name = "related_id")
    private Long relatedId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
