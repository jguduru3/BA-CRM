package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "ai_automation_runs")
@Getter @Setter
public class AiAutomationRun {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "automation_id", nullable = false)
    private Long automationId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    // running | completed | failed
    @Column(nullable = false, length = 30)
    private String status = "running";

    @Column(name = "trigger_payload_json", columnDefinition = "TEXT")
    private String triggerPayloadJson;

    @Column(name = "resolved_input_json", columnDefinition = "TEXT")
    private String resolvedInputJson;

    @Column(name = "output_text", columnDefinition = "TEXT")
    private String outputText;

    @Column(name = "action_result_json", columnDefinition = "TEXT")
    private String actionResultJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "triggered_by")
    private Long triggeredBy;
}
