package com.betteranalytics.crm.entity;

import com.betteranalytics.crm.security.EncryptedStringConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** An outbound webhook endpoint that receives HMAC-signed CRM change events. */
@Entity
@Table(name = "webhook_subscriptions")
@Getter
@Setter
public class WebhookSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 1000)
    private String url;

    /** Signing secret — encrypted at rest, returned to the client only on create/rotate. */
    @JsonIgnore
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "TEXT")
    private String secret;

    /** Comma-separated event names, "lead.*"-style wildcards, or "*" for every event. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String events;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "last_delivery_at")
    private Instant lastDeliveryAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public boolean matches(String event) {
        if (events == null) return false;
        for (String e : events.split(",")) {
            String t = e.trim();
            if (t.equals("*") || t.equals(event)) return true;
            if (t.endsWith(".*") && event.startsWith(t.substring(0, t.length() - 1))) return true;
        }
        return false;
    }
}
