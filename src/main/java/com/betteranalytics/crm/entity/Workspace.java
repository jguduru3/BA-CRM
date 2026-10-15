package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "workspaces")
@Getter @Setter
public class Workspace {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 50)
    private String icon = "grid";

    @Column(length = 20)
    private String color = "#29ABE2";

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    /**
     * Mirror of isDefault that the database can enforce (V40): equals
     * organizationId on the org's single default workspace, NULL otherwise, and
     * carries a UNIQUE index. Kept in sync automatically before every write.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "default_org_id")
    private Long defaultOrgId;

    @PrePersist
    @PreUpdate
    void syncDefaultMirror() {
        this.defaultOrgId = isDefault ? organizationId : null;
    }

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
