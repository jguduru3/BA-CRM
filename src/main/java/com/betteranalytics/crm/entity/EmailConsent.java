package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Current email consent state of one address in one organization (V80). No row = opted in. */
@Entity
@Table(name = "email_consent")
@Getter @Setter
public class EmailConsent {
    public static final String OPTED_IN = "opted_in";
    public static final String OPTED_OUT = "opted_out";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "email_normalized", nullable = false, length = 320)
    private String emailNormalized;

    // opted_in | opted_out
    @Column(nullable = false, length = 20)
    private String status;

    // unsubscribe_link | reply_keyword | manual
    @Column(length = 30)
    private String source;

    @Column(length = 500)
    private String reason;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
