package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Campaign;
import com.betteranalytics.crm.entity.CampaignAudience;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.CampaignAudienceRepository;
import com.betteranalytics.crm.repository.CampaignRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.CampaignAudienceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

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

    private static final int MAX_RETURNED_IDS = 5000;

    private final CampaignAudienceRepository audienceRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignAudienceService audienceService;
    private final PermissionService permissionService;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public CampaignAudienceController(CampaignAudienceRepository audienceRepository,
                                       CampaignRepository campaignRepository,
                                       CampaignAudienceService audienceService,
                                       PermissionService permissionService,
                                       CurrentUser currentUser, AuditService auditService,
                                       ObjectMapper objectMapper) {
        this.audienceRepository = audienceRepository;
        this.campaignRepository = campaignRepository;
        this.audienceService = audienceService;
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

        List<CampaignAudienceService.Filter> filters = audienceService.parse(audience.getCriteriaJson(), true);
        long total = audienceService.count(actor.getOrganizationId(), filters, false);
        List<Long> matchingIds = evaluateIds(audience.getCriteriaJson(), actor.getOrganizationId());
        audience.setEstimatedSize((int) Math.min(Integer.MAX_VALUE, total));
        audience.setLastEvaluated(Instant.now());
        audience.setUpdatedAt(Instant.now());
        audienceRepository.save(audience);

        return ResponseEntity.ok(Map.of(
                "estimatedSize", total,
                "matchingIds", matchingIds.size() > MAX_RETURNED_IDS ? matchingIds.subList(0, MAX_RETURNED_IDS) : matchingIds,
                "truncated", total > MAX_RETURNED_IDS,
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

    private void parseFilters(String criteriaJson) {
        audienceService.parse(criteriaJson, true);
    }

    /** Evaluated in the database: COUNT + a capped id SELECT (CampaignAudienceService). */
    private List<Long> evaluateIds(String criteriaJson, Long orgId) {
        return audienceService.ids(orgId, audienceService.parse(criteriaJson, true), MAX_RETURNED_IDS + 1);
    }
}
