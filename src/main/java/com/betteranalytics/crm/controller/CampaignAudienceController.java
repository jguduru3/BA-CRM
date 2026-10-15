package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Campaign;
import com.betteranalytics.crm.entity.CampaignAudience;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.CampaignAudienceRepository;
import com.betteranalytics.crm.repository.CampaignRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Audience builder for campaigns. Every endpoint first resolves the campaign
 * inside the caller's organization (404 otherwise) and scopes audiences to it.
 *
 * POST   /api/campaigns/{campaignId}/audiences               — save filter criteria
 * GET    /api/campaigns/{campaignId}/audiences               — list saved audiences
 * POST   /api/campaigns/{campaignId}/audiences/{id}/evaluate — run criteria, return matching lead ids
 * DELETE /api/campaigns/{campaignId}/audiences/{id}
 */
@RestController
@RequestMapping("/api/campaigns/{campaignId}/audiences")
public class CampaignAudienceController {

    private static final Set<String> FIELDS = Set.of("stage", "priority", "industry", "geography", "source", "value");
    private static final Set<String> OPS = Set.of("eq", "neq", "in", "gte", "lte", "gt", "lt");
    private static final int MAX_FILTERS = 50;
    private static final int MAX_RETURNED_IDS = 5000;

    private final CampaignAudienceRepository audienceRepository;
    private final CampaignRepository campaignRepository;
    private final LeadRepository leadRepository;
    private final PermissionService permissionService;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CampaignAudienceController(CampaignAudienceRepository audienceRepository,
                                       CampaignRepository campaignRepository,
                                       LeadRepository leadRepository,
                                       PermissionService permissionService,
                                       CurrentUser currentUser, AuditService auditService,
                                       ObjectMapper objectMapper) {
        this.audienceRepository = audienceRepository;
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<CampaignAudience>> list(@PathVariable Long campaignId) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        if (findCampaign(campaignId, actor) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(audienceRepository.findByCampaignIdAndOrganizationIdOrderByCreatedAtDesc(
                campaignId, actor.getOrganizationId()));
    }

    @PostMapping
    public ResponseEntity<CampaignAudience> create(@PathVariable Long campaignId,
                                                    @RequestBody CampaignAudience body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        if (findCampaign(campaignId, actor) == null) return ResponseEntity.notFound().build();
        parseFilters(body.getCriteriaJson());   // 400 on malformed criteria

        CampaignAudience a = new CampaignAudience();
        a.setOrganizationId(actor.getOrganizationId());
        a.setCampaignId(campaignId);
        String name = body.getName();
        a.setName(name == null ? null : (name.length() > 200 ? name.substring(0, 200) : name));
        a.setCriteriaJson(body.getCriteriaJson());
        a.setEstimatedSize(0);
        a.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        audienceRepository.save(a);
        auditService.log("create", "campaign_audiences", a.getId(), a.getName(), null, a);
        return ResponseEntity.status(201).body(a);
    }

    /**
     * Evaluate the audience criteria and return matching record ids + estimated size.
     * Supported filters on leads: stage, priority, industry, geography, source, value (gte/lte/eq).
     */
    @PostMapping("/{id}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable Long campaignId,
                                                         @PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        if (findCampaign(campaignId, actor) == null) return ResponseEntity.notFound().build();
        CampaignAudience audience = audienceRepository
                .findByIdAndCampaignIdAndOrganizationId(id, campaignId, actor.getOrganizationId()).orElse(null);
        if (audience == null) return ResponseEntity.notFound().build();

        List<Long> matchingIds = evaluateCriteria(audience.getCriteriaJson(), actor.getOrganizationId());
        audience.setEstimatedSize(matchingIds.size());
        audience.setLastEvaluated(Instant.now());
        audience.setUpdatedAt(Instant.now());
        audienceRepository.save(audience);

        return ResponseEntity.ok(Map.of(
                "estimatedSize", matchingIds.size(),
                "matchingIds", matchingIds.size() > MAX_RETURNED_IDS ? matchingIds.subList(0, MAX_RETURNED_IDS) : matchingIds,
                "truncated", matchingIds.size() > MAX_RETURNED_IDS,
                "evaluatedAt", audience.getLastEvaluated().toString()
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long campaignId, @PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        if (findCampaign(campaignId, actor) == null) return ResponseEntity.notFound().build();
        CampaignAudience existing = audienceRepository
                .findByIdAndCampaignIdAndOrganizationId(id, campaignId, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        audienceRepository.delete(existing);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Campaign findCampaign(Long campaignId, User actor) {
        return campaignRepository.findByIdAndOrganizationId(campaignId, actor.getOrganizationId()).orElse(null);
    }

    // ── Criteria evaluation engine ────────────────────────────────────────────
    // Evaluates JSON filter spec against in-memory leads fetched for the org.
    // For large datasets, move to a JPA Specification or native query.
    //
    // criteria_json format:
    //   { "entity": "lead",
    //     "filters": [
    //       { "field": "stage",    "op": "eq",  "value":  "Qualified" },
    //       { "field": "industry", "op": "in",  "values": ["Technology"] },
    //       { "field": "value",    "op": "gte", "value":  100000 }
    //     ]
    //   }

    /** Parses and validates criteria; any problem → IllegalArgumentException (400). */
    private List<Map<String, Object>> parseFilters(String criteriaJson) {
        if (criteriaJson == null || criteriaJson.isBlank())
            throw new IllegalArgumentException("criteriaJson is required");
        Map<String, Object> criteria;
        try {
            criteria = objectMapper.readValue(criteriaJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("criteriaJson must be a JSON object");
        }
        if (criteria == null) throw new IllegalArgumentException("criteriaJson must be a JSON object");
        Object entity = criteria.get("entity");
        if (entity != null && !"lead".equals(entity))
            throw new IllegalArgumentException("criteriaJson.entity must be 'lead'");
        Object rawFilters = criteria.getOrDefault("filters", List.of());
        if (!(rawFilters instanceof List<?> list)) throw new IllegalArgumentException("criteriaJson.filters must be an array");
        if (list.size() > MAX_FILTERS) throw new IllegalArgumentException("Too many filters (max " + MAX_FILTERS + ")");
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("each filter must be an object");
            Object field = m.get("field");
            Object op = m.get("op") == null ? "eq" : m.get("op");
            if (!(field instanceof String fieldName) || !FIELDS.contains(fieldName))
                throw new IllegalArgumentException("filter.field must be one of " + FIELDS);
            if (!(op instanceof String s) || !OPS.contains(s))
                throw new IllegalArgumentException("filter.op must be one of " + OPS);
            if ("in".equals(op) && !(m.get("values") instanceof List))
                throw new IllegalArgumentException("filter.values must be an array for op 'in'");
            Map<String, Object> f = new HashMap<>();
            f.put("field", field);
            f.put("op", op);
            f.put("value", m.get("value"));
            f.put("values", m.get("values"));
            filters.add(f);
        }
        return filters;
    }

    private List<Long> evaluateCriteria(String criteriaJson, Long orgId) {
        List<Map<String, Object>> filters = parseFilters(criteriaJson);
        return leadRepository.findByOrganizationId(orgId)
                .stream()
                .filter(lead -> filters.stream().allMatch(f -> matchesFilter(lead, f)))
                .map(Lead::getId)
                .collect(Collectors.toList());
    }

    private boolean matchesFilter(Lead lead, Map<String, Object> filter) {
        String field = (String) filter.get("field");
        String op    = (String) filter.get("op");
        Object value = filter.get("value");
        List<?> values = filter.get("values") instanceof List<?> l ? l : null;

        Object fieldVal = switch (field) {
            case "stage"     -> lead.getStage();
            case "priority"  -> lead.getPriority();
            case "industry"  -> lead.getIndustry();
            case "geography" -> lead.getGeography();
            case "source"    -> lead.getSource();
            case "value"     -> lead.getValue();
            default          -> null;
        };
        if (fieldVal == null) return false;

        return switch (op) {
            case "eq"  -> String.valueOf(fieldVal).equalsIgnoreCase(String.valueOf(value));
            case "neq" -> !String.valueOf(fieldVal).equalsIgnoreCase(String.valueOf(value));
            case "in"  -> values != null && values.stream()
                    .anyMatch(v -> String.valueOf(fieldVal).equalsIgnoreCase(String.valueOf(v)));
            case "gte" -> toDouble(fieldVal) >= toDouble(value);
            case "lte" -> toDouble(fieldVal) <= toDouble(value);
            case "gt"  -> toDouble(fieldVal) >  toDouble(value);
            case "lt"  -> toDouble(fieldVal) <  toDouble(value);
            default    -> false;
        };
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return 0; }
    }
}
