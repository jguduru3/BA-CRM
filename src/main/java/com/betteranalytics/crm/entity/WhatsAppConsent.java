package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Current WhatsApp consent state of one number in one organization (V30). */
@Entity
@Table(name = "whatsapp_consents")
@Getter @Setter
public class WhatsAppConsent {
    public static final String OPTED_IN = "opted_in";
    public static final String OPTED_OUT = "opted_out";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "normalized_phone", nullable = false, length = 20)
    private String normalizedPhone;

    // opted_in | opted_out
    @Column(nullable = false, length = 20)
    private String status;

    // inbound_keyword | manual | import
    @Column(nullable = false, length = 30)
    private String source;

    @Column(length = 500)
    private String reason;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
