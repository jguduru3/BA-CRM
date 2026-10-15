package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "campaign_contacts")
@Getter @Setter
public class CampaignContact {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    // Polymorphic link — Lead | Contact
    @Column(name = "related_type", length = 20)
    private String relatedType;

    @Column(name = "related_id")
    private Long relatedId;

    // E.164 phone number
    @Column(name = "phone_number", nullable = false, length = 30)
    private String phoneNumber;

    @Column(length = 200)
    private String name;

    // pending | sent | delivered | read | failed | opted_out
    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Column(name = "wa_message_id", length = 200)
    private String waMessageId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "failed_reason", length = 300)
    private String failedReason;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
