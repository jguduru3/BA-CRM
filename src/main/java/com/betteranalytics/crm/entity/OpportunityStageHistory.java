package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only log of an opportunity's stage changes (V60). The first row of a
 * deal has {@code fromStage == null}. Durations are written on the row that
 * ends a stint, so velocity is a plain average over {@code secondsInFromStage}.
 */
@Entity
@Table(name = "opportunity_stage_history")
@Getter
@Setter
public class OpportunityStageHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "opportunity_id", nullable = false, updatable = false)
    private Long opportunityId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "from_stage", length = 30, updatable = false)
    private String fromStage;

    @Column(name = "to_stage", nullable = false, length = 30, updatable = false)
    private String toStage;

    @Column(name = "changed_by", updatable = false)
    private Long changedBy;

    @Column(name = "entered_at", nullable = false, updatable = false)
    private Instant enteredAt;

    @Column(name = "seconds_in_from_stage", updatable = false)
    private Long secondsInFromStage;

    @Column(name = "seconds_since_created", updatable = false)
    private Long secondsSinceCreated;
}
