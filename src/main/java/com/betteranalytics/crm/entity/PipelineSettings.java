package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Per-organisation opportunity pipeline configuration (V20): the default win
 * probability for each stage (JSON object stage → 0..100) and the deal value at
 * or above which a deal counts as "big" and alerts the manager chain.
 */
@Entity
@Table(name = "pipeline_settings")
@Getter
@Setter
public class PipelineSettings {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    @Column(name = "stage_probabilities", nullable = false, columnDefinition = "TEXT")
    private String stageProbabilities;

    /** V61 — JSON object stage → forecast category key; null = defaults. */
    @Column(name = "stage_forecast_categories", columnDefinition = "TEXT")
    private String stageForecastCategories;

    @Column(name = "big_deal_threshold")
    private Long bigDealThreshold;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
