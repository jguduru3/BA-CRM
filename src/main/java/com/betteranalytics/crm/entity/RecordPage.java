package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "record_pages")
@Getter @Setter
public class RecordPage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    // Lead | Opportunity | Contact | Company | CustomRecord
    @Column(name = "related_type", nullable = false, length = 30)
    private String relatedType;

    @Column(name = "related_id", nullable = false)
    private Long relatedId;

    @Column(name = "cover_color", length = 20)
    private String coverColor;

    @Column(length = 50)
    private String icon;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
