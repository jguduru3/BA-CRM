package com.betteranalytics.crm.dedupe;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Per-organization duplicate rule for one object type (V67). */
@Entity
@Table(name = "duplicate_rules")
@Getter
@Setter
public class DuplicateRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false, length = 20)
    private ObjectType objectType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_on_create", nullable = false, length = 10)
    private DuplicateAction actionOnCreate = DuplicateAction.WARN;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_on_edit", nullable = false, length = 10)
    private DuplicateAction actionOnEdit = DuplicateAction.WARN;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** The default rule for an object type that has no stored row: enabled, WARN / WARN. */
    public static DuplicateRule defaults(Long orgId, ObjectType type) {
        DuplicateRule r = new DuplicateRule();
        r.setOrganizationId(orgId);
        r.setObjectType(type);
        return r;
    }
}
