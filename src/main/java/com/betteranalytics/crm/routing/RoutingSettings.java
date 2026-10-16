package com.betteranalytics.crm.routing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-organization round-robin lead routing (V68). The rotation pointer
 * (lastAssignedUserId) is advanced only by {@link RoutingSettingsRepository#advance},
 * a compare-and-set on {@code version}; the settings form never writes it.
 */
@Entity
@Table(name = "routing_settings")
@Getter
@Setter
public class RoutingSettings {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private boolean enabled = false;

    /** Rotate within this team's members; null = every active sales rep of the org. */
    @Column(name = "team_id")
    private Long teamId;

    /** FIELD_SALES reps only receive leads whose geography matches their territory. */
    @Column(name = "respect_territory", nullable = false)
    private boolean respectTerritory = false;

    @Column(name = "last_assigned_user_id")
    private Long lastAssignedUserId;

    // Not @Version: the CAS UPDATE manages it, and settings saves must not conflict with rotation.
    @Column(nullable = false)
    private long version = 0;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
