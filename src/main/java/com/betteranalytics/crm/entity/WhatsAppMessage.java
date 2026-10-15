package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "whatsapp_messages")
@Getter @Setter
public class WhatsAppMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "account_id")
    private Long accountId;

    // inbound | outbound
    @Column(nullable = false, length = 10)
    private String direction;

    // Meta's wa_message_id
    @Column(name = "wa_message_id", length = 200)
    private String waMessageId;

    // E.164 phone — for inbound: the sender; for outbound: null (use toNumber)
    @Column(name = "from_number", length = 30)
    private String fromNumber;

    // E.164 phone — for outbound: recipient; for inbound: our number (null ok)
    @Column(name = "to_number", length = 30)
    private String toNumber;

    // text | template | image | document | audio | video | interactive
    @Column(nullable = false, length = 20)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "media_url", length = 500)
    private String mediaUrl;

    @Column(name = "media_mime_type", length = 100)
    private String mediaMimeType;

    @Column(name = "template_name", length = 100)
    private String templateName;

    // JSON array of template component parameter values
    @Column(name = "template_params", columnDefinition = "TEXT")
    private String templateParams;

    // sent | delivered | read | failed | received
    @Column(nullable = false, length = 20)
    private String status = "sent";

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "related_type", length = 20)
    private String relatedType;

    @Column(name = "related_id")
    private Long relatedId;

    @Column(name = "sent_by")
    private Long sentBy;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
