package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.Task;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {

    private final LeadRepository        leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final TaskRepository        taskRepository;
    private final UserRepository        userRepository;
    private final CurrentUser           currentUser;
    private final PermissionService     permissionService;

    public ReportsController(LeadRepository leadRepository,
                              OpportunityRepository opportunityRepository,
                              TaskRepository taskRepository,
                              UserRepository userRepository,
                              CurrentUser currentUser,
                              PermissionService permissionService) {
        this.leadRepository        = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.taskRepository        = taskRepository;
        this.userRepository        = userRepository;
        this.currentUser           = currentUser;
        this.permissionService     = permissionService;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        User actor = currentUser.get();
        Long orgId = currentUser.organizationId();

        List<Lead>        leads = visibleLeads(actor, orgId);
        List<Opportunity> opps  = visibleOpps(actor, orgId);
        List<Task>        tasks = scopedTasks(actor, orgId);

        long activeLeads  = leads.stream().filter(l -> !CLOSED.contains(l.getStage())).count();
        long totalOppValue = sumValues(opps.stream().filter(o -> !CLOSED.contains(o.getStage())).toList());
        List<Opportunity> won = opps.stream().filter(o -> "Won".equals(o.getStage())).toList();
        long wonValue     = sumValues(won);
        // H14: Inside Sales may never see opportunity values (PermissionService
        // .canViewOpportunityValue) — the per-record mask was bypassed here by
        // aggregating. Null renders as "—" in the UI, same as /pipeline.
        boolean showValues = permissionService.canViewOpportunityValue(actor);
        long openTasks    = tasks.stream().filter(t -> !"Done".equals(t.getStatus())).count();
        long overdueTasks = tasks.stream().filter(t -> !"Done".equals(t.getStatus())
                && t.getDueDate() != null
                && t.getDueDate().isBefore(java.time.LocalDate.now())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalLeads",    leads.size());
        result.put("activeLeads",   activeLeads);
        result.put("totalOppValue", showValues ? totalOppValue : null);
        result.put("wonValue",      showValues ? wonValue : null);
        result.put("wonOpps",       won.size());
        result.put("openTasks",     openTasks);
        result.put("overdueTasks",  overdueTasks);
        // Show data scope so the UI can label the card correctly
        result.put("scope", scopeLabel(actor));
        return result;
    }

    @GetMapping("/pipeline")
    public List<Map<String, Object>> pipeline() {
        User actor = currentUser.get();
        List<Opportunity> opps = visibleOpps(actor, currentUser.organizationId());
        List<String> stages = List.of("Qualification", "Proposal", "Negotiation", "Verbal Commit", "Won", "Lost");
        return stages.stream().map(stage -> {
            List<Opportunity> inStage = opps.stream().filter(o -> stage.equals(o.getStage())).toList();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("count", inStage.size());
            // Inside Sales cannot see opp value — return null so UI renders "—"
            row.put("value", permissionService.canViewOpportunityValue(actor) ? sumValues(inStage) : null);
            return row;
        }).collect(Collectors.toList());
    }

    @GetMapping("/funnel")
    public List<Map<String, Object>> funnel() {
        User actor = currentUser.get();
        List<Lead> leads = visibleLeads(actor, currentUser.organizationId());
        List<String> stages = List.of("New", "Contacted", "Meeting", "Proposal", "Negotiation", "Won");
        return stages.stream().map(stage -> {
            long count = leads.stream().filter(l -> stage.equals(l.getStage())).count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("count", count);
            return row;
        }).collect(Collectors.toList());
    }

    @GetMapping("/lead-sources")
    public List<Map<String, Object>> leadSources() {
        User actor = currentUser.get();
        List<Lead> leads = visibleLeads(actor, currentUser.organizationId());
        Map<String, Long> counts = leads.stream()
                .collect(Collectors.groupingBy(
                        l -> Optional.ofNullable(l.getSource()).orElse("Unknown"),
                        Collectors.counting()));
        return counts.entrySet().stream()
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
        Long orgId = currentUser.organizationId();

        // Only MANAGER+ roles can see full team stats; reps see only themselves
        List<User> users = userRepository.findByOrganizationId(orgId).stream()
                .filter(u -> {
                    if (actor.getRole() == Role.SUPER_ADMIN || actor.getRole() == Role.VP_SALES) {
                        return true; // all org members
                    }
                    if (actor.getRole() == Role.MANAGER) {
                        // Direct reports: users whose reportsToId points to this manager, plus self
                        return Objects.equals(u.getReportsToId(), actor.getId()) || u.getId().equals(actor.getId());
                    }
                    return u.getId().equals(actor.getId()); // reps see only themselves
                })
                .collect(Collectors.toList());

        List<Lead>        leads = leadRepository.findByOrganizationId(orgId);
        List<Opportunity> opps  = opportunityRepository.findByOrganizationId(orgId);
        boolean showValues = permissionService.canViewOpportunityValue(actor);

        return users.stream().map(u -> {
            long leadsOwned    = leads.stream().filter(l -> u.getId().equals(l.getOwnerId())).count();
            long pipelineValue = sumValues(opps.stream()
                    .filter(o -> u.getId().equals(o.getOwnerId()) && !CLOSED.contains(o.getStage()))
                    .toList());
            long wonValue = sumValues(opps.stream()
                    .filter(o -> u.getId().equals(o.getOwnerId()) && "Won".equals(o.getStage()))
                    .toList());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",            u.getId());
            row.put("name",          u.getName());
            row.put("role",          u.getRole());
            row.put("initials",      u.getInitials());
            row.put("color",         u.getColor());
            row.put("leadsOwned",    leadsOwned);
            row.put("pipelineValue", showValues ? pipelineValue : null);
            row.put("wonValue",      showValues ? wonValue : null);
            return row;
        }).collect(Collectors.toList());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static final List<String> CLOSED = List.of("Won", "Lost");

    /** M13: legacy rows can carry a null value; treat as 0 instead of NPE-ing the whole report. */
    private static long sumValues(List<Opportunity> opps) {
        return opps.stream().map(Opportunity::getValue).filter(Objects::nonNull).mapToLong(Long::longValue).sum();
    }

    private List<Lead> visibleLeads(User actor, Long orgId) {
        return leadRepository.findByOrganizationId(orgId).stream()
                .filter(l -> permissionService.can(actor, Action.VIEW,
                        ResourceType.LEAD, l.getOrganizationId(), l.getOwnerId()))
                .collect(Collectors.toList());
    }

    private List<Opportunity> visibleOpps(User actor, Long orgId) {
        List<Opportunity> base = opportunityRepository.findByOrganizationId(orgId).stream()
                .filter(o -> permissionService.can(actor, Action.VIEW,
                        ResourceType.OPPORTUNITY, o.getOrganizationId(), o.getOwnerId()))
                .collect(Collectors.toList());

        // Inside Sales also see sourced opps in reports (stage only)
        if (actor.getRole() == Role.INSIDE_SALES) {
            Set<Long> baseIds = base.stream().map(Opportunity::getId).collect(Collectors.toSet());
            List<Opportunity> sourced = opportunityRepository
                    .findByOrganizationIdAndSourcedByUserId(orgId, actor.getId());
            sourced.stream()
                    .filter(o -> !baseIds.contains(o.getId()))
                    .forEach(base::add);
        }
        return base;
    }

    private List<Task> scopedTasks(User actor, Long orgId) {
        List<Task> all = taskRepository.findByOrganizationId(orgId);
        if (actor.getRole() == Role.SUPER_ADMIN || actor.getRole() == Role.VP_SALES) return all;
        // Task has no teamId; for MANAGER scope to their visible users
        // For now, filter by assigneeId which is the practical equivalent
        return all.stream()
                .filter(t -> actor.getId().equals(t.getAssigneeId()))
                .collect(Collectors.toList());
    }

    private String scopeLabel(User actor) {
        return switch (actor.getRole()) {
            case SUPER_ADMIN, VP_SALES -> "org";
            case MANAGER              -> "team";
            default                   -> "own";
        };
    }
}
