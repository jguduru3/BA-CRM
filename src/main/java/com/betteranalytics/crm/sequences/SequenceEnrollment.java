package com.betteranalytics.crm.sequences;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Lead or Contact going through a sequence.
 *
 * Status: ACTIVE → (PAUSED ↔ ACTIVE) → COMPLETED | RESPONDED | STOPPED | FAILED.
 * {@code activeKey} is set only while ACTIVE/PAUSED so a unique constraint
 * allows one live enrollment per (sequence, record) without a partial index.
 */
@Entity
@Table(name = "sequence_enrollments")
@Getter
@Setter
public class SequenceEnrollment {

    public static final String ACTIVE = "ACTIVE";
    public static final String PAUSED = "PAUSED";
    public static final String RESPONDED = "RESPONDED";
    public static final String COMPLETED = "COMPLETED";
    public static final String STOPPED = "STOPPED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "sequence_id", nullable = false)
    private Long sequenceId;

    @Column(name = "related_type", nullable = false, length = 20)
    private String relatedType;

    @Column(name = "related_id", nullable = false)
    private Long relatedId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "enrolled_by")
    private Long enrolledBy;

    @Column(nullable = false, length = 20)
    private String status = ACTIVE;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "skipped_channels", length = 100)
    private String skippedChannels;

    @Column(name = "contact_email", length = 320)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "active_key", length = 100)
    private String activeKey;

    @Column(name = "stop_reason", length = 300)
    private String stopReason;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static String activeKeyFor(Long sequenceId, String relatedType, Long relatedId) {
        return sequenceId + ":" + relatedType + ":" + relatedId;
    }

    public boolean isLive() {
        return ACTIVE.equals(status) || PAUSED.equals(status);
    }

    public Set<String> skippedChannelSet() {
        if (skippedChannels == null || skippedChannels.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(skippedChannels.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** @return true when the channel was newly added */
    public boolean addSkippedChannel(String channel) {
        Set<String> s = skippedChannelSet();
        boolean added = s.add(channel);
        skippedChannels = s.isEmpty() ? null : String.join(",", s);
        return added;
    }

    /** Moves to a terminal state and frees the live-enrollment slot. */
    public void finish(String terminalStatus, String reason, Instant now) {
        status = terminalStatus;
        activeKey = null;
        nextRunAt = null;
        claimedAt = null;
        if (reason != null) stopReason = reason.length() > 300 ? reason.substring(0, 300) : reason;
        if (RESPONDED.equals(terminalStatus)) respondedAt = now;
        if (COMPLETED.equals(terminalStatus)) completedAt = now;
        updatedAt = now;
    }
}
