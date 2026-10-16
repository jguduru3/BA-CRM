package com.betteranalytics.crm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** A user's forecast target for one month or quarter (V62). */
@Entity
@Table(name = "forecast_quotas")
@Getter
@Setter
public class ForecastQuota {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** "month" or "quarter". */
    @Column(name = "period_type", nullable = false, length = 10)
    private String periodType;

    /** First day of the month / quarter. */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private Long amount;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
