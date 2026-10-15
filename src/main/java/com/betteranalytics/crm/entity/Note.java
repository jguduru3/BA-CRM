package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "notes")
@Getter @Setter
public class Note {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(length = 300)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    // Lead | Opportunity | Contact | Company | Meeting
    @Column(name = "related_type", length = 30)
    private String relatedType;

    @Column(name = "related_id")
    private Long relatedId;

    @Column(name = "is_pinned", nullable = false)
    private boolean pinned = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;
}
