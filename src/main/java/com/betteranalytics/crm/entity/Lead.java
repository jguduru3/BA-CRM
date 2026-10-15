package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
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
@DynamicUpdate
@Entity
@Table(name = "leads")
@Getter
@Setter
public class Lead {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    private String email;
    private String phone; // "contact" in the data feed

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 20)
    private String stage = "New"; // = "lead status"

    @Column(nullable = false, length = 30)
    private String source = "Direct"; // = "lead source"

    @Column(nullable = false, length = 10)
    private String priority = "Medium";

    // Column is deal_value, not value: VALUE is a reserved word in H2 (the
    // local-dev profile's engine) though not in Postgres — renamed in the DB
    // rather than worked around, since nothing had shipped against V1 yet.
    // Field/JSON name stays "value" — only the physical column differs.
    @Column(name = "deal_value")
    private Long value = 0L;

    @Column(columnDefinition = "TEXT")
    private String notes; // = "lead notes"

    private String tags;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @Column(name = "converted_opp_id")
    private Long convertedOppId;

    @Column(name = "follow_up_at")
    private Instant followUpAt;

    // ── NEW — data-feed intake fields ──────────────────────────
    @Column(length = 400)
    private String address;

    @Column(name = "business_name", length = 200)
    private String businessName; // captured at intake, before a Company record exists

    @Column(length = 30)
    private String category; // Industry | In-house | Other services

    @Column(name = "lead_type", length = 30)
    private String leadType; // Inbound | Outbound | Referral | Partner

    // ── NEW — qualification / discovery fields ──────────────────
    @Column(length = 100)
    private String industry;

    @Column(length = 150)
    private String geography;

    @Column(name = "employee_band", length = 30)
    private String employeeBand; // e.g. "1-10", "11-50", "51-200", "201-1000", "1000+"

    @Column(name = "revenue_band", length = 30)
    private String revenueBand; // e.g. "<1Cr", "1-5Cr", "5-25Cr", "25Cr+"

    @Column(columnDefinition = "TEXT")
    private String requirement;

    @Column(name = "pain_point", columnDefinition = "TEXT")
    private String painPoint;

    @Column(name = "technology_used", length = 300)
    private String technologyUsed;

    @Column(name = "decision_maker", length = 200)
    private String decisionMaker; // name/title of the actual decision maker contact

    @Column(name = "lead_score")
    private Integer leadScore = 0;

    // V5 — Manager score override reason (set alongside leadScore when a Manager
    // uses POST /api/leads/{id}/score-override). Null = system-calculated score.
    @Column(name = "lead_score_override_reason", length = 500)
    private String leadScoreOverrideReason;

    // V5 — how many times follow_up_at has been pushed out (each reschedule is
    // counted and shown on the Manager's dashboard per spec §4).
    @Column(name = "follow_up_reschedule_count")
    private Integer followUpRescheduleCount = 0;

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

    // V20 — optimistic locking: leads are edited by users and background jobs.
    // Clients echo it back on update; a stale value is answered with 409 STALE_UPDATE.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // V20 — when the owner was told this lead's follow-up is overdue (once per date).
    @Column(name = "follow_up_notified_at")
    private Instant followUpNotifiedAt;
}
