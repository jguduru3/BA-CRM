package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import java.time.Instant;

@Entity
@Table(name = "campaigns")
@DynamicUpdate   // only changed columns are written, so status saves never clobber dispatcher counters
@Getter @Setter
public class Campaign {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    // whatsapp | email | sms  (column name is "type" in DB)
    @Column(nullable = false, length = 20)
    private String type = "whatsapp";

    // draft | submitted | approved | rejected | scheduled | sending | paused | completed | cancelled
    // (legacy pending_approval / running were renamed by V30)
    @Column(nullable = false, length = 25)
    private String status = "draft";

    // Which WhatsApp account to use for this campaign
    @Column(name = "wa_account_id")
    private Long waAccountId;

    // For WhatsApp: the approved template name
    @Column(name = "template_name", length = 100)
    private String templateName;

    // WhatsApp template language code, e.g. en_US (V15)
    @Column(name = "template_language", length = 20)
    private String templateLanguage = "en_US";

    // JSON: array of template component parameter values
    @Column(name = "template_params", columnDefinition = "TEXT")
    private String templateParams;

    // For text/sms campaigns
    @Column(name = "message_body", columnDefinition = "TEXT")
    private String messageBody;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "total_contacts")
    private Integer totalContacts = 0;

    @Column(name = "sent_count")
    private Integer sentCount = 0;

    @Column(name = "delivered_count")
    private Integer deliveredCount = 0;

    @Column(name = "read_count")
    private Integer readCount = 0;

    @Column(name = "failed_count")
    private Integer failedCount = 0;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "owner_id")
    private Long ownerId;

    // Approval workflow fields (V10)
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    // Optional inline audience criteria JSON (alternative to CampaignAudience table)
    @Column(name = "audience_criteria_json", columnDefinition = "TEXT")
    private String audienceCriteriaJson;

    // Optimistic lock (V30): concurrent status changes → 409 instead of a lost update.
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
