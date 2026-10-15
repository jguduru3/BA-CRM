package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Temporary delegation of TEAM scope from a Manager (delegator) to a named
 * delegate for a bounded date range, per the spec's "Hierarchy edge cases":
 * "Temporary delegation when a Manager is on leave: a named delegate gets
 * Team scope for set dates."
 *
 * PermissionService reads active rows (today in [start_date, end_date]) when
 * resolving TEAM scope in isInTeamScope(), so a delegate can approve, view,
 * and manage the delegator's team records during the window without needing
 * a permanent role change.
 */
@Entity
@Table(name = "delegations")
@Getter
@Setter
public class Delegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** The Manager who is granting access. */
    @Column(name = "delegator_id", nullable = false)
    private Long delegatorId;

    /** The user who receives TEAM scope for the delegator's teams. */
    @Column(name = "delegate_id", nullable = false)
    private Long delegateId;

    /**
     * Scope the delegation to a single team; null = all teams the delegator leads.
     * Kept at the data layer for future use; PermissionService treats null as
     * "all of delegator's teams" which is the common case.
     */
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
