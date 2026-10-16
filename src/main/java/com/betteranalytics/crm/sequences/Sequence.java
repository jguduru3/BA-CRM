package com.betteranalytics.crm.sequences;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/** A multi-channel outreach sequence (ordered {@link SequenceStep}s). Soft-deleted rows are invisible. */
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "sequences")
@Getter
@Setter
public class Sequence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    /** Sending mailbox; null = the enrollment owner's own active email account. */
    @Column(name = "email_account_id")
    private Long emailAccountId;

    /** WhatsApp sender; null = the organization's first active account. */
    @Column(name = "whatsapp_account_id")
    private Long whatsappAccountId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Version
    private Long version;
}
