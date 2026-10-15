package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "activities")
@Getter
@Setter
public class Activity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 20)
    private String type; // call | email | meeting | note | whatsapp | linkedin | task | stage_change

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "related_type", length = 20)
    private String relatedType; // Lead | Opportunity | Contact | Company

    @Column(name = "related_id")
    private Long relatedId;

    private String outcome;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // NEW — "when should we touch this record again", distinct from
    // scheduledAt/completedAt which describe THIS activity's own timing.
    @Column(name = "next_follow_up_at")
    private Instant nextFollowUpAt;

    // V5 — correction link: points to the activity this row corrects.
    // The timeline is append-only; mistakes are fixed by adding a correction
    // entry linked to the original, per spec §4.
    @Column(name = "correction_for_id")
    private Long correctionForId;

    // V5 — call/meeting duration in minutes (logged at create time).
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
