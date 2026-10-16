package com.betteranalytics.crm.routing;

import com.betteranalytics.crm.entity.Team;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.TenantGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Round-robin lead routing settings — Manager and above. */
@RestController
@RequestMapping("/api/routing-settings")
public class RoutingController {

    private final RoutingService routing;
    private final CurrentUser currentUser;
    private final TenantGuard tenantGuard;
    private final AuditService auditService;

    public RoutingController(RoutingService routing, CurrentUser currentUser, TenantGuard tenantGuard,
                             AuditService auditService) {
        this.routing = routing;
        this.currentUser = currentUser;
        this.tenantGuard = tenantGuard;
        this.auditService = auditService;
    }

    @GetMapping
    public Map<String, Object> get() {
        User actor = requireManagerUp();
        return view(routing.settings(actor.getOrganizationId()), true);
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody JsonNode body) {
        User actor = requireManagerUp();
        RoutingSettings current = routing.settings(actor.getOrganizationId());
        boolean enabled = body.has("enabled") ? bool(body, "enabled") : current.isEnabled();
        boolean respectTerritory = body.has("respectTerritory") ? bool(body, "respectTerritory") : current.isRespectTerritory();
        Long teamId = current.getTeamId();
        if (body.has("teamId")) {
            JsonNode t = body.get("teamId");
            if (t.isNull() || (t.isTextual() && (t.asText().isBlank() || "all".equalsIgnoreCase(t.asText())))) {
                teamId = null;
            } else if (t.canConvertToLong()) {
                Team team = tenantGuard.requireTeam(t.asLong());
                teamId = team.getId();
            } else {
                throw new IllegalArgumentException("teamId must be a team id, or null for all reps");
            }
        }
        Map<String, Object> before = view(current, false);
        RoutingSettings saved = routing.save(actor.getOrganizationId(), enabled, teamId, respectTerritory, actor.getId());
        Map<String, Object> after = view(saved, false);
        auditService.log("update", "routing_settings", saved.getId(), "Lead routing", before, after);
        return view(saved, true);
    }

    private Map<String, Object> view(RoutingSettings s, boolean withPreview) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.isEnabled());
        m.put("teamId", s.getTeamId());
        m.put("respectTerritory", s.isRespectTerritory());
        m.put("lastAssignedUserId", s.getLastAssignedUserId());
        m.put("updatedAt", s.getUpdatedAt());
        if (withPreview) {
            // who is currently in the rotation (ignoring territory, which depends on each lead)
            RoutingSettings noTerritory = new RoutingSettings();
            noTerritory.setOrganizationId(s.getOrganizationId());
            noTerritory.setTeamId(s.getTeamId());
            List<Map<String, Object>> reps = routing.eligible(noTerritory, null).stream().map(u -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", u.getId());
                r.put("name", u.getName());
                r.put("role", u.getRole().name());
                r.put("territory", u.getTerritory());
                return r;
            }).toList();
            m.put("eligibleReps", reps);
        }
        return m;
    }

    private static boolean bool(JsonNode body, String field) {
        JsonNode v = body.get(field);
        if (v == null || !v.isBoolean()) throw new IllegalArgumentException(field + " must be true or false");
        return v.asBoolean();
    }

    private User requireManagerUp() {
        User actor = currentUser.get();
        if (!RoutingService.isManagerUp(actor)) {
            throw new AccessDeniedException("only a Manager or above can manage lead routing");
        }
        return actor;
    }
}
