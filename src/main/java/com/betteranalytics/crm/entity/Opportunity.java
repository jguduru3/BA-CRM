package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

// Soft-deleted rows are invisible to every normal query through this
// entity (list, search, findById, dashboards, JPQL @Query methods) —
// nothing has to remember a "deleted_at IS NULL" filter by hand.
// Deliberately bypassed only by the native-SQL trash/restore queries
// in the matching repository, which is the one place that SHOULD see them.
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "opportunities")
@Getter
@Setter
public class Opportunity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "contact_id")
    private Long contactId;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 30)
    private String stage = "Qualification";

    // Column is deal_value, not value — see Lead.value for why.
    @Column(name = "deal_value", nullable = false)
    private Long value = 0L;

    @Column(nullable = false)
    private Integer probability = 50;

    @Column(name = "close_date")
    private LocalDate closeDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // ── NEW — deal-context fields ────────────────────────────────
    @Column(name = "service_product", length = 200)
    private String serviceProduct;

    @Column(length = 300)
    private String competitors;

    @Column(name = "current_vendor", length = 200)
    private String currentVendor;

    @Column(name = "proposal_status", length = 30)
    private String proposalStatus; // Not Sent | Sent | Viewed | Revised

    @Column(name = "negotiation_status", length = 30)
    private String negotiationStatus; // Not Started | In Progress | Final Offer | Agreed

    @Column(name = "po_status", length = 30)
    private String poStatus; // Not Requested | Requested | Received | Payment Pending | Paid

    // V5 — back-link to the lead that was converted into this opportunity.
    // Enables Inside Sales "own sourced" visibility: they can see the stage of
    // opps that came from leads they qualified (spec §5, §7 build notes).
    @Column(name = "source_lead_id")
    private Long sourceLeadId;

    @Column(name = "sourced_by_user_id")
    private Long sourcedByUserId; // the user who owned the lead at conversion time

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
