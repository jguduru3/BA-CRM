package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.Aggregates;
import com.betteranalytics.crm.core.Aggregates.Bucket;
import com.betteranalytics.crm.core.PipelineSettingsService;
import com.betteranalytics.crm.core.ScopeSpecs;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.CurrentUser;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard and report figures. Every number is a GROUP BY / COUNT / SUM in the
 * database over the reader's record scope (the same predicates as the list
 * endpoints) — nothing loads whole tables into memory.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportsController {

    private static final List<String> CLOSED = List.of("Won", "Lost");

    private final UserRepository    userRepository;
    private final CurrentUser       currentUser;
    private final PermissionService permissionService;
    private final ScopeSpecs        scopes;
    private final Aggregates        aggregates;

    public ReportsController(UserRepository userRepository, CurrentUser currentUser,
                             PermissionService permissionService, ScopeSpecs scopes, Aggregates aggregates) {
        this.userRepository    = userRepository;
        this.currentUser       = currentUser;
        this.permissionService = permissionService;
        this.scopes            = scopes;
        this.aggregates        = aggregates;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        User actor = currentUser.get();
        Map<Object, Bucket> leadsByStage = leadStages(actor);
        Map<Object, Bucket> oppsByStage = oppStages(actor);

        long totalLeads = leadsByStage.values().stream().mapToLong(Bucket::count).sum();
        long activeLeads = leadsByStage.entrySet().stream()
                .filter(e -> !CLOSED.contains(String.valueOf(e.getKey()))).mapToLong(e -> e.getValue().count()).sum();
        long totalOppValue = oppsByStage.entrySet().stream()
                .filter(e -> !CLOSED.contains(String.valueOf(e.getKey()))).mapToLong(e -> e.getValue().sum()).sum();
        Bucket won = oppsByStage.getOrDefault("Won", new Bucket(0, 0));
        // H14: Inside Sales may never see opportunity values — not even aggregated.
        boolean showValues = permissionService.canViewOpportunityValue(actor);

        long openTasks = 0, overdueTasks = 0;
        Specification<Task> tasks = scopes.tasks(actor);
        if (tasks != null) {
            Specification<Task> open = tasks.and((root, q, cb) -> cb.notEqual(root.get("status"), "Done"));
            openTasks = aggregates.count(Task.class, open);
            LocalDate today = LocalDate.now();
            overdueTasks = aggregates.count(Task.class, open.and((root, q, cb) -> cb.lessThan(root.get("dueDate"), today)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalLeads",    totalLeads);
        result.put("activeLeads",   activeLeads);
        result.put("totalOppValue", showValues ? totalOppValue : null);
        result.put("wonValue",      showValues ? won.sum() : null);
        result.put("wonOpps",       won.count());
        result.put("openTasks",     openTasks);
        result.put("overdueTasks",  overdueTasks);
        result.put("scope", scopeLabel(actor));
        return result;
    }

    @GetMapping("/pipeline")
    public List<Map<String, Object>> pipeline() {
        User actor = currentUser.get();
        Map<Object, Bucket> byStage = oppStages(actor);
        boolean showValues = permissionService.canViewOpportunityValue(actor);
        return PipelineSettingsService.STAGES.stream().map(stage -> {
            Bucket b = byStage.getOrDefault(stage, new Bucket(0, 0));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("count", b.count());
            // Inside Sales cannot see opp value — null renders as "—"
            row.put("value", showValues ? b.sum() : null);
            return row;
        }).collect(Collectors.toList());
    }

    @GetMapping("/funnel")
    public List<Map<String, Object>> funnel() {
        Map<Object, Bucket> byStage = leadStages(currentUser.get());
        List<String> stages = List.of("New", "Contacted", "Meeting", "Proposal", "Negotiation", "Won");
        return stages.stream().map(stage -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("count", byStage.getOrDefault(stage, new Bucket(0, 0)).count());
            return row;
        }).collect(Collectors.toList());
    }

    @GetMapping("/lead-sources")
    public List<Map<String, Object>> leadSources() {
        Specification<Lead> leads = scopes.leads(currentUser.get());
        if (leads == null) return List.of();
        Map<Object, Bucket> bySource = aggregates.groupBy(Lead.class, leads, "source", null);
        Map<String, Long> merged = new LinkedHashMap<>();
        bySource.forEach((k, v) -> merged.merge(k == null ? "Unknown" : String.valueOf(k), v.count(), Long::sum));
        return merged.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/team")
    public List<Map<String, Object>> team() {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();

        // Full team stats for Manager+; reps see only themselves.
        List<User> users = userRepository.findByOrganizationId(orgId).stream()
                .filter(u -> {
                    if (actor.getRole() == Role.SUPER_ADMIN || actor.getRole() == Role.VP_SALES) return true;
                    if (actor.getRole() == Role.MANAGER) return permissionService.isManagedBy(actor, u.getId());
                    return u.getId().equals(actor.getId());
                })
                .collect(Collectors.toList());

        Specification<Lead> orgLeads = Specs.org(orgId);
        Specification<Opportunity> orgOpps = Specs.org(orgId);
        Map<Object, Bucket> leadsByOwner = aggregates.groupBy(Lead.class, orgLeads, "ownerId", null);
        Map<Object, Bucket> openByOwner = aggregates.groupBy(Opportunity.class,
                orgOpps.and((root, q, cb) -> cb.not(root.get("stage").in(CLOSED))), "ownerId", "value");
        Map<Object, Bucket> wonByOwner = aggregates.groupBy(Opportunity.class,
                orgOpps.and(Specs.eq("stage", "Won")), "ownerId", "value");
        boolean showValues = permissionService.canViewOpportunityValue(actor);
        Bucket zero = new Bucket(0, 0);

        return users.stream().map(u -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",            u.getId());
            row.put("name",          u.getName());
            row.put("role",          u.getRole());
            row.put("initials",      u.getInitials());
            row.put("color",         u.getColor());
            row.put("leadsOwned",    leadsByOwner.getOrDefault(u.getId(), zero).count());
            row.put("pipelineValue", showValues ? openByOwner.getOrDefault(u.getId(), zero).sum() : null);
            row.put("wonValue",      showValues ? wonByOwner.getOrDefault(u.getId(), zero).sum() : null);
            return row;
        }).collect(Collectors.toList());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Map<Object, Bucket> leadStages(User actor) {
        Specification<Lead> leads = scopes.leads(actor);
        return leads == null ? Map.of() : aggregates.groupBy(Lead.class, leads, "stage", null);
    }

    private Map<Object, Bucket> oppStages(User actor) {
        Specification<Opportunity> opps = scopes.opportunities(actor);
        return opps == null ? Map.of() : aggregates.groupBy(Opportunity.class, opps, "stage", "value");
    }

    private String scopeLabel(User actor) {
        return switch (actor.getRole()) {
            case SUPER_ADMIN, VP_SALES -> "org";
            case MANAGER              -> "team";
            default                   -> "own";
        };
    }
}
