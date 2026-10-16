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
