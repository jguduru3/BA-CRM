package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "database_properties")
@Getter @Setter
public class DatabaseProperty {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "database_id", nullable = false)
    private Long databaseId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    // text | number | date | select | multi_select | relation | checkbox | url | email | phone | file | formula
    @Column(nullable = false, length = 20)
    private String type;

    // JSON array of {id, label, color} for select/multi_select
    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    // for relation type: which other database this field references
    @Column(name = "relation_db_id")
    private Long relationDbId;

    @Column(nullable = false)
    private boolean required = false;

    @Column(nullable = false)
    private int position = 0;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
