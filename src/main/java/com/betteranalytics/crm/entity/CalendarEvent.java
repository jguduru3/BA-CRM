package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "calendar_events")
@Getter @Setter
public class CalendarEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay = false;

    // event | meeting | reminder | task | deadline
    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType = "event";

    @Column(name = "related_type", length = 50)
    private String relatedType;

    @Column(name = "related_id")
    private Long relatedId;

    @Column(name = "color_hex", length = 10)
    private String colorHex;

    @Column(length = 300)
    private String location;

    @Column(name = "recurrence_rule", length = 200)
    private String recurrenceRule;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
