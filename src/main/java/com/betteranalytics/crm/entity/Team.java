package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "teams")
@Getter
@Setter
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // The Sales Manager (or whoever) leading this team. This is how
    // "Sales Manager sees their team's records" will be resolved later —
    // no separate teamId field is needed on User; membership lives in
    // TeamMember, and "who leads this team" lives here.
    @Column(name = "leader_id")
    private Long leaderId;

    // Department support isn't built yet (no Department entity/controller),
    // but the column already exists from V1 — kept as a plain nullable id
    // rather than a JPA relation until Department is actually needed.
    @Column(name = "department_id")
    private Long departmentId;

    @Column(length = 20)
    private String color = "#0078d4";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
