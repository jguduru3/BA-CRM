package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "campaign_audiences")
@Getter @Setter
public class CampaignAudience {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(length = 200)
    private String name;

    // JSON audience filter specification:
    // { "entity": "lead", "filters": [{ "field": "industry", "op": "in", "values": [...] }, ...] }
    @Column(name = "criteria_json", columnDefinition = "TEXT", nullable = false)
    private String criteriaJson;

    @Column(name = "estimated_size")
    private Integer estimatedSize = 0;

    @Column(name = "last_evaluated")
    private Instant lastEvaluated;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
