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

    // E.164 phone number (optional for email recipients since V80)
    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    // Email recipients (V80): address as entered and its lowercase form,
    // unique per campaign — an address is never emailed twice by a campaign.
    @Column(length = 320)
    private String email;

    @Column(name = "email_normalized", length = 320)
    private String emailNormalized;

    @Column(length = 200)
    private String name;

    // pending | sending | sent | delivered | read | failed | skipped_opted_out | skipped
    @Column(nullable = false, length = 20)
    private String status = "pending";

    // Provider message id: WhatsApp wamid, email Message-ID or Azure SMS message id
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

    // E.164 with leading '+' (V30). Unique per campaign — a number is never messaged twice.
    @Column(name = "normalized_phone", length = 20)
    private String normalizedPhone;

    // When a dispatcher claimed this recipient ('sending'); stale claims are reset.
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
