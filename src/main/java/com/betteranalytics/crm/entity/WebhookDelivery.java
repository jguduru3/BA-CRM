package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One delivery attempt of one event to one webhook subscription. */
@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
public class WebhookDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String event;

    @Column(name = "delivery_uid", nullable = false, length = 64)
    private String deliveryUid;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(nullable = false)
    private boolean success = false;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(length = 1000)
    private String error;

    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
