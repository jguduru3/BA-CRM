package com.betteranalytics.crm.sequences;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The at-most-once record of one step for one enrollment: unique
 * (enrollment_id, step_index), written (PENDING) before any send is attempted.
 */
@Entity
@Table(name = "sequence_step_executions")
@Getter
@Setter
public class SequenceStepExecution {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "enrollment_id", nullable = false)
    private Long enrollmentId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(name = "step_id")
    private Long stepId;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String detail;

    @Column(name = "wa_message_id", length = 200)
    private String waMessageId;

    @Column(name = "whatsapp_message_id")
    private Long whatsappMessageId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
