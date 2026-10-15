package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Append-only consent history: who/what changed a number's consent, when and why (V30). */
@Entity
@Table(name = "whatsapp_consent_events")
@Getter @Setter
public class WhatsAppConsentEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "normalized_phone", nullable = false, length = 20)
    private String normalizedPhone;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 30)
    private String source;

    @Column(length = 500)
    private String reason;

    // null when the subscriber changed it themselves (inbound STOP/START)
    @Column(name = "actor_user_id")
    private Long actorUserId;

    // inbound WhatsApp message that carried the keyword, if any
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
