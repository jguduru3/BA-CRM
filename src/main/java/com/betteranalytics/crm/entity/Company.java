package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

// Soft-deleted rows are invisible to every normal query through this
// entity (list, search, findById, dashboards, JPQL @Query methods) —
// nothing has to remember a "deleted_at IS NULL" filter by hand.
// Deliberately bypassed only by the native-SQL trash/restore queries
// in the matching repository, which is the one place that SHOULD see them.
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "companies")
@Getter
@Setter
public class Company {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    private String industry;
    private String size;
    private String website;
    private String phone;
    private String email;
    private String location;
    private Long revenue = 0L;

    @Column(nullable = false, length = 20)
    private String status = "Prospect";

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── NEW — soft delete (see V4 migration + @SQLRestriction below) ────
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "delete_reason", length = 500)
    private String deleteReason;
}
