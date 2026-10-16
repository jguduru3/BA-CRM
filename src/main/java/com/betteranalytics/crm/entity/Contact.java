package com.betteranalytics.crm.entity;

import com.betteranalytics.crm.dedupe.MatchKeys;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
@Table(name = "contacts")
@Getter
@Setter
public class Contact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 120)
    private String name;

    private String title;
    private String email;
    private String phone;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 20)
    private String status = "Active";

    private String source;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private String tags;
    private String linkedin;

    @Column(name = "last_contacted_at")
    private Instant lastContactedAt;

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

    @Column(name = "category_id")
    private Long categoryId;

    // ── NEW — segmentation fields for WhatsApp/campaign audience building.
    // Mirrors Lead.industry/geography so Leads and Contacts share one
    // vocabulary across the funnel (spec: "Audience Builder — filters such
    // as industry, location, ... deal value").
    @Column(length = 100)
    private String industry;

    @Column(length = 100)
    private String country;

    @Column(length = 100)
    private String state;

    @Column(name = "nature_of_business", length = 150)
    private String natureOfBusiness;

    // ── V65 — normalized duplicate-match keys, maintained by dedupe.MatchKeys on
    // every insert/update. Internal only: never serialized (they would bypass
    // contact-info masking).
    @JsonIgnore @Column(name = "email_normalized")
    private String emailNormalized;

    @JsonIgnore @Column(name = "phone_normalized", length = 32)
    private String phoneNormalized;

    @JsonIgnore @Column(name = "domain_normalized")
    private String domainNormalized;

    @JsonIgnore @Column(name = "name_key")
    private String nameKey;

    @JsonIgnore @Column(name = "name_phonetic", length = 16)
    private String namePhonetic;

    // V65 — comma-separated ids of the records merged into this one (merge is irreversible).
    @Column(name = "merged_ids", columnDefinition = "TEXT")
    private String mergedIds;

    @PrePersist
    @PreUpdate
    void refreshMatchKeys() {
        MatchKeys.refresh(this);
    }
}
