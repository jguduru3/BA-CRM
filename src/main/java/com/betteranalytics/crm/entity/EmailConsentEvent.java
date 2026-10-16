package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Append-only history of email consent changes (V80). */
@Entity
@Table(name = "email_consent_events")
@Getter @Setter
public class EmailConsentEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "email_normalized", nullable = false, length = 320)
    private String emailNormalized;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 30)
    private String source;

    @Column(length = 500)
    private String reason;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
