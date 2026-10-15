package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.PipelineSettings;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.PipelineSettingsRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.service.NotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Organisation-level pipeline configuration: default win probability per stage
 * and the "big deal" threshold that alerts managers.
 */
@Service
public class PipelineSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PipelineSettingsService.class);

    /** Pipeline stages, in order. Must match the frontend's OPP_STAGES. */
    public static final List<String> STAGES =
            List.of("Qualification", "Proposal", "Negotiation", "Verbal Commit", "Won", "Lost");
    public static final Set<String> CLOSED_STAGES = Set.of("Won", "Lost");

    static final Map<String, Integer> DEFAULT_PROBABILITIES;
    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Qualification", 10);
        m.put("Proposal", 30);
        m.put("Negotiation", 60);
        m.put("Verbal Commit", 80);
        m.put("Won", 100);
        m.put("Lost", 0);
        DEFAULT_PROBABILITIES = Collections.unmodifiableMap(m);
    }
    public static final long DEFAULT_BIG_DEAL_THRESHOLD = 1_000_000L;

    private final PipelineSettingsRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public PipelineSettingsService(PipelineSettingsRepository repository, UserRepository userRepository,
                                   NotificationService notificationService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    /** Effective settings for an org (defaults when none are stored). */
    public record Settings(Map<String, Integer> stageProbabilities, Long bigDealThreshold) {
        public int probabilityFor(String stage) {
            Integer p = stageProbabilities.get(stage);
            if (p == null) p = DEFAULT_PROBABILITIES.getOrDefault(stage, 50);
            return p;
        }
    }

    public Settings get(Long orgId) {
        return repository.findByOrganizationId(orgId).map(this::toSettings)
                .orElseGet(() -> new Settings(DEFAULT_PROBABILITIES, DEFAULT_BIG_DEAL_THRESHOLD));
    }

    private Settings toSettings(PipelineSettings s) {
        Map<String, Integer> merged = new LinkedHashMap<>(DEFAULT_PROBABILITIES);
        try {
            Map<String, Integer> stored = objectMapper.readValue(s.getStageProbabilities(), new TypeReference<>() {});
            stored.forEach((k, v) -> { if (STAGES.contains(k) && v != null) merged.put(k, v); });
        } catch (Exception e) {
            log.warn("pipeline_settings for org {} has unreadable stage_probabilities; using defaults", s.getOrganizationId());
        }
        return new Settings(Collections.unmodifiableMap(merged), s.getBigDealThreshold());
    }

    /** Validates and stores new settings; returns {before, after}. */
    public Settings[] save(Long orgId, Long actorId, Map<String, Integer> probabilities, Long threshold,
                           boolean thresholdPresent) {
        Settings before = get(orgId);
        Map<String, Integer> merged = new LinkedHashMap<>(before.stageProbabilities());
        if (probabilities != null) {
            for (Map.Entry<String, Integer> e : probabilities.entrySet()) {
                if (!STAGES.contains(e.getKey())) {
                    throw new IllegalArgumentException("unknown stage '" + e.getKey() + "'; stages are " + STAGES);
                }
                Integer v = e.getValue();
                if (v == null || v < 0 || v > 100) {
                    throw new IllegalArgumentException("probability for " + e.getKey() + " must be between 0 and 100");
                }
                merged.put(e.getKey(), v);
            }
        }
        Long newThreshold = thresholdPresent ? threshold : before.bigDealThreshold();
        if (newThreshold != null && newThreshold < 0) throw new IllegalArgumentException("bigDealThreshold must not be negative");

        PipelineSettings row = repository.findByOrganizationId(orgId).orElseGet(() -> {
            PipelineSettings n = new PipelineSettings();
            n.setOrganizationId(orgId);
            n.setCreatedAt(Instant.now());
            return n;
        });
        try {
            row.setStageProbabilities(objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            throw new IllegalStateException("could not store pipeline settings");
        }
        row.setBigDealThreshold(newThreshold);
        row.setUpdatedBy(actorId);
        row.setUpdatedAt(Instant.now());
        repository.save(row);
        return new Settings[]{before, new Settings(Collections.unmodifiableMap(merged), newThreshold)};
    }

    // ── Big-deal alerts ──────────────────────────────────────────────────────────

    public boolean isBigDeal(Long orgId, Opportunity opp) {
        Long threshold = get(orgId).bigDealThreshold();
        return threshold != null && opp.getValue() != null && opp.getValue() >= threshold;
    }

    /**
     * Notify the owner's manager chain (walking reportsToId upwards) plus every VP
     * Sales / Super Admin of the org that a big deal was created or moved stage.
     * The actor never notifies themselves.
     */
    public int alertBigDeal(User actor, Opportunity opp, String what) {
        Long orgId = opp.getOrganizationId();
        Set<Long> recipients = new LinkedHashSet<>();
        Long cursor = opp.getOwnerId();
        Set<Long> seen = new HashSet<>();
        for (int depth = 0; cursor != null && depth < 10 && seen.add(cursor); depth++) {
            User u = userRepository.findByIdAndOrganizationId(cursor, orgId).orElse(null);
            if (u == null) break;
            if (!u.getId().equals(opp.getOwnerId()) && u.isActive()) recipients.add(u.getId());
            cursor = u.getReportsToId();
        }
        for (User u : userRepository.findByOrganizationId(orgId)) {
            if ((u.getRole() == Role.VP_SALES || u.getRole() == Role.SUPER_ADMIN) && u.isActive()) recipients.add(u.getId());
        }
        if (actor != null) recipients.remove(actor.getId());
        String title = "Big deal " + what + ": " + opp.getName();
        String body = "Value " + opp.getValue() + " · stage " + opp.getStage();
        for (Long uid : recipients) {
            notificationService.notify(orgId, uid, "big_deal", title, body, "Opportunity", opp.getId());
        }
        return recipients.size();
    }
}
