package com.betteranalytics.crm.sequences;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Append-only history of an enrollment (enrolled, step sent/skipped/failed, responded, paused, ...). */
@Entity
@Table(name = "sequence_enrollment_events")
@Getter
@Setter
public class SequenceEnrollmentEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "enrollment_id", nullable = false)
    private Long enrollmentId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "step_index")
    private Integer stepIndex;

    @Column(length = 20)
    private String channel;

    @Column(length = 500)
    private String detail;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
