package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * Write-once audit log — never update or delete rows.
 * before_json/after_json hold JSON snapshots (kept as TEXT here for
 * portability between H2 dev and Azure Postgres; switch to a jsonb
 * column + @JdbcTypeCode(SqlTypes.JSON) once running on Postgres only).
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_name", length = 150)
    private String userName;

    @Column(nullable = false, length = 50)
    private String action; // create | update | delete | login | export

    @Column(nullable = false, length = 100)
    private String resource; // leads | opportunities | users | ...

    @Column(name = "resource_id", length = 50)
    private String resourceId;

    @Column(name = "resource_name", length = 300)
    private String resourceName;

    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
