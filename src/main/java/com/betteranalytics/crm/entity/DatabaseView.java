package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "database_views")
@Getter @Setter
public class DatabaseView {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "database_id", nullable = false)
    private Long databaseId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    // table | kanban | calendar | timeline | dashboard
    @Column(nullable = false, length = 20)
    private String type;

    // JSON: { filters, sorts, groupBy, visibleProperties, kanbanProperty, calendarProperty, timelineStartProp, timelineEndProp }
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(nullable = false)
    private int position = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
