package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "device_tokens")
@Getter @Setter
public class DeviceToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Azure Notification Hubs installation id ("web-" + hash of the browser push endpoint)
    @Column(nullable = false, length = 500)
    private String token;

    // android | ios | web
    @Column(nullable = false, length = 10)
    private String platform;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
