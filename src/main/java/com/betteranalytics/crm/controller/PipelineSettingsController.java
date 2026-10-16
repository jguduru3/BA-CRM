package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.Patch;
import com.betteranalytics.crm.core.PipelineSettingsService;
import com.betteranalytics.crm.core.PipelineSettingsService.Settings;
import com.betteranalytics.crm.entity.ForecastCategory;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Organisation pipeline settings: default win probability per opportunity stage
 * (applied on create / stage change unless a deal's probability was overridden)
 * and the big-deal alert threshold. Everyone may read them (the deal form shows
 * the stage defaults); only Manager+ may change them.
 */
@RestController
@RequestMapping("/api/pipeline-settings")
public class PipelineSettingsController {

    private final PipelineSettingsService service;
    private final CurrentUser currentUser;
    private final AuditService auditService;

    public PipelineSettingsController(PipelineSettingsService service, CurrentUser currentUser, AuditService auditService) {
        this.service = service;
        this.currentUser = currentUser;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> get() {
        return toJson(service.get(currentUser.organizationId()));
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody JsonNode body) {
        User actor = currentUser.get();
        Role r = actor.getRole();
        if (r != Role.SUPER_ADMIN && r != Role.VP_SALES && r != Role.MANAGER) {
            throw new AccessDeniedException("only a Manager or above can change pipeline settings");
        }
        Patch p = Patch.of(body);
        Map<String, Integer> probabilities = null;
        JsonNode probs = body == null ? null : body.get("stageProbabilities");
        if (probs != null && !probs.isNull()) {
            if (!probs.isObject()) throw new IllegalArgumentException("stageProbabilities must be an object of stage → 0..100");
            probabilities = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = probs.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isNumber()) throw new IllegalArgumentException("probability for " + e.getKey() + " must be a number");
                probabilities.put(e.getKey(), e.getValue().asInt());
            }
        }
        Map<String, String> categories = null;
        JsonNode cats = body == null ? null : body.get("stageForecastCategories");
        if (cats != null && !cats.isNull()) {
            if (!cats.isObject()) throw new IllegalArgumentException("stageForecastCategories must be an object of stage → category");
            categories = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = cats.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isTextual()) throw new IllegalArgumentException("forecast category for " + e.getKey() + " must be a string");
                categories.put(e.getKey(), e.getValue().asText());
            }
        }
        Settings[] change = service.save(actor.getOrganizationId(), actor.getId(), probabilities,
                p.lng("bigDealThreshold"), p.has("bigDealThreshold"), categories);
        auditService.log("update", "pipeline_settings", actor.getOrganizationId(), "Pipeline settings",
                toJson(change[0]), toJson(change[1]));
        return toJson(change[1]);
    }

    private static Map<String, Object> toJson(Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stages", PipelineSettingsService.STAGES);
        m.put("stageProbabilities", s.stageProbabilities());
        m.put("bigDealThreshold", s.bigDealThreshold());
        Map<String, String> cats = new LinkedHashMap<>();
        PipelineSettingsService.STAGES.forEach(st -> cats.put(st, s.categoryFor(st).name()));
        m.put("stageForecastCategories", cats);
        List<Map<String, Object>> all = new ArrayList<>();
        for (ForecastCategory c : ForecastCategory.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", c.name());
            row.put("label", c.label());
            row.put("order", c.ordinal());
            all.add(row);
        }
        m.put("forecastCategories", all);
        return m;
    }
}
