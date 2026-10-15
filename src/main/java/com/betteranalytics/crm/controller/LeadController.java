package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.dto.LeadRequest;
import com.betteranalytics.crm.dto.PipelineResponse;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.repository.OpportunityRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AiAutomationService;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private static final Set<String> PIPELINE_GROUP_BY = Set.of("stage", "source", "leadType", "category");

    private final LeadRepository        leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final CurrentUser           currentUser;
    private final AuditService          auditService;
    private final PermissionService     permissionService;
    private final NotificationService   notificationService;
    private final AiAutomationService   aiAutomationService;
    private final TenantGuard           tenantGuard;

    public LeadController(LeadRepository leadRepository, OpportunityRepository opportunityRepository,
                           CurrentUser currentUser, AuditService auditService,
                           PermissionService permissionService,
                           NotificationService notificationService,
                           AiAutomationService aiAutomationService,
                           TenantGuard tenantGuard) {
        this.leadRepository        = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.currentUser           = currentUser;
        this.auditService          = auditService;
        this.permissionService     = permissionService;
        this.notificationService   = notificationService;
        this.aiAutomationService   = aiAutomationService;
        this.tenantGuard           = tenantGuard;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String search,
                                     @RequestParam(defaultValue = "200") int limit) {
        User actor = currentUser.get();
        List<Lead> leads = leadRepository.search(currentUser.organizationId(), search).stream()
                .filter(l -> permissionService.can(actor, Action.VIEW, ResourceType.LEAD,
                                                   l.getOrganizationId(), l.getOwnerId()))
                .collect(Collectors.toList());
        List<Lead> page = leads.stream().limit(TenantGuard.clampLimit(limit)).collect(Collectors.toList());
        return Map.of("count", leads.size(),
                      "data",  permissionService.maskLeadsForResponse(actor, page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Lead> get(@PathVariable Long id) {
        Lead lead = find(id).orElse(null);
        if (lead == null) throw new NoSuchElementException("lead not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.LEAD,
                                  lead.getOrganizationId(), lead.getOwnerId());
        return ResponseEntity.ok(permissionService.maskLeadForResponse(actor, lead));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody LeadRequest req) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.LEAD,
                                  currentUser.organizationId(), null);
        if (req.name == null || req.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (req.ownerId != null && !req.ownerId.equals(actor.getId())) {
            requireAssignableOwner(actor, req.ownerId);
        }
        tenantGuard.requireCompany(req.companyId);
        // Lead score is system-computed; only Manager+ may override it (spec §3).
        // The create form may send the default score, so for other roles the
        // field is simply ignored here rather than rejected.
        if (!permissionService.canOverrideLeadScore(actor)) req.leadScore = null;

        Lead lead = new Lead();
        apply(lead, req);
        lead.setOrganizationId(currentUser.organizationId());
        lead.setOwnerId(req.ownerId != null ? req.ownerId : currentUser.userId());
        lead.setFollowUpRescheduleCount(0);
        lead.setCreatedAt(Instant.now());
        lead.setUpdatedAt(Instant.now());
        leadRepository.save(lead);
        auditService.log("create", "leads", lead.getId(), lead.getName(), null, lead);
        if (!lead.getOwnerId().equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), lead.getOwnerId(),
                    "lead_assigned", "Lead assigned to you: " + lead.getName(),
                    "Assigned by " + actor.getName(), "Lead", lead.getId());
        }
        aiAutomationService.fireEvent(lead.getOrganizationId(), "on_lead_created", leadContext(lead));
        return ResponseEntity.status(201).body(permissionService.maskLeadForResponse(actor, lead));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody LeadRequest req) {
        Lead existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("lead not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.LEAD,
                                  existing.getOrganizationId(), existing.getOwnerId());

        // Field-mask enforcement (step 4 of PermissionService fixed order)
        if (req.source != null && !req.source.equals(existing.getSource())
                && !permissionService.canEditLeadSource(actor, false)) {
            throw new AccessDeniedException("lead source is locked after creation for your role");
        }
        if (req.ownerId != null && !req.ownerId.equals(existing.getOwnerId())) {
            requireAssignableOwner(actor, req.ownerId);
        }
        // Lead score: changing it through a plain edit would bypass the audited
        // score-override flow, so it's Manager+ only here as well.
        if (req.leadScore != null && !req.leadScore.equals(existing.getLeadScore())
                && !permissionService.canOverrideLeadScore(actor)) {
            throw new AccessDeniedException("lead score can only be overridden by a Manager or above");
        }
        if (req.name != null && req.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name cannot be blank"));
        }
        if (req.companyId != null && !req.companyId.equals(existing.getCompanyId())) {
            tenantGuard.requireCompany(req.companyId);
        }
        // V5: Field Sales cannot change geography — a change re-routes the lead
        if (req.geography != null && !req.geography.equals(existing.getGeography())
                && !permissionService.canEditLeadGeography(actor)) {
            throw new AccessDeniedException(
                    "geography determines territory routing; request the change via your Manager");
        }

        // V5: track follow-up reschedule count
        boolean followUpChanged = req.followUpAt != null
                && !req.followUpAt.equals(existing.getFollowUpAt());
        if (followUpChanged && existing.getFollowUpAt() != null) {
            // Only count as a reschedule when there was already a date set
            existing.setFollowUpRescheduleCount(
                    (existing.getFollowUpRescheduleCount() == null ? 0
                            : existing.getFollowUpRescheduleCount()) + 1);
        }

        Long previousOwnerId = existing.getOwnerId();
        String previousStage = existing.getStage();
        Lead before = copy(existing);
        apply(existing, req);
        existing.setUpdatedAt(Instant.now());
        leadRepository.save(existing);
        auditService.log("update", "leads", existing.getId(), existing.getName(), before, existing);
        if (existing.getStage() != null && !existing.getStage().equals(previousStage)) {
            aiAutomationService.fireEvent(existing.getOrganizationId(), "on_stage_change", leadContext(existing));
        }
        Long newOwnerId = existing.getOwnerId();
        if (newOwnerId != null && !newOwnerId.equals(previousOwnerId) && !newOwnerId.equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), newOwnerId,
                    "lead_assigned", "Lead assigned to you: " + existing.getName(),
                    "Reassigned by " + actor.getName(), "Lead", existing.getId());
        }
        return ResponseEntity.ok(permissionService.maskLeadForResponse(actor, existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id,
                                     @RequestParam(required = false) String reason) {
        Lead existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("lead not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.LEAD,
                                  existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(currentUser.userId());
        existing.setDeleteReason(reason);
        leadRepository.save(existing);
        auditService.log("delete", "leads", id, existing.getName(), existing,
                         Map.of("reason", reason == null ? "" : reason));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id) {
        Lead existing = leadRepository.findByIdAndOrganizationIdIncludingDeleted(
                id, currentUser.organizationId()).orElse(null);
        if (existing == null) throw new NoSuchElementException("lead not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.LEAD,
                                  existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(null);
        existing.setDeletedBy(null);
        existing.setDeleteReason(null);
        leadRepository.save(existing);
        auditService.log("update", "leads", id, existing.getName(), null, Map.of("restored", true));
        return ResponseEntity.ok(permissionService.maskLeadForResponse(actor, existing));
    }

    @GetMapping("/trash")
    public List<Lead> trash() {
        User actor = currentUser.get();
        return leadRepository.findTrashedByOrganizationId(currentUser.organizationId()).stream()
                .filter(l -> permissionService.can(actor, Action.DELETE, ResourceType.LEAD,
                                                   l.getOrganizationId(), l.getOwnerId()))
                .map(l -> permissionService.maskLeadForResponse(actor, l))
                .collect(Collectors.toList());
    }

    /**
     * V5 — Claim a lead from the unassigned pool.
     * Spec §2: "Claim from unassigned pool — Executive: Yes, Inside Sales: Yes,
     * Field Sales: Own territory (lead.geography matches user.territory)."
     * SUPER_ADMIN/VP_SALES/MANAGER use assign/reassign instead of this endpoint.
     */
    @PostMapping("/{id}/claim")
    public ResponseEntity<?> claim(@PathVariable Long id) {
        Lead lead = find(id).orElse(null);
        if (lead == null) throw new NoSuchElementException("lead not found");

        User actor = currentUser.get();
        Role role = actor.getRole();

        // Only rep-level roles can claim; managers use the assign endpoint
        if (role != Role.EXECUTIVE && role != Role.INSIDE_SALES && role != Role.FIELD_SALES) {
            throw new AccessDeniedException(
                    "use POST /api/leads/{id}/assign to reassign leads for your role");
        }
        if (lead.getOwnerId() != null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "lead is already assigned",
                                 "ownerId", lead.getOwnerId()));
        }

        // Field Sales can only claim leads in their territory
        if (role == Role.FIELD_SALES) {
            String territory = actor.getTerritory();
            String geography = lead.getGeography();
            if (territory == null || geography == null
                    || !geography.toLowerCase().contains(territory.toLowerCase())) {
                return ResponseEntity.status(403)
                        .body(Map.of("error", "lead geography does not match your territory",
                                     "territory", territory == null ? "" : territory,
                                     "geography", geography == null ? "" : geography));
            }
        }

        Lead before = copy(lead);
        Long previousOwnerId = lead.getOwnerId();
        lead.setOwnerId(actor.getId());
        lead.setUpdatedAt(Instant.now());
        leadRepository.save(lead);
        auditService.log("update", "leads", lead.getId(), lead.getName(), before,
                         Map.of("claimed_by", actor.getId()));
        if (previousOwnerId != null && !previousOwnerId.equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), previousOwnerId,
                    "lead_claimed", "Lead claimed: " + lead.getName(),
                    actor.getName() + " claimed this lead", "Lead", lead.getId());
        }
        return ResponseEntity.ok(permissionService.maskLeadForResponse(actor, lead));
    }

    /**
     * V5 — Manager override of lead score with a mandatory reason.
     * Spec §3: "Lead score — Manager: View; override with reason".
     * Overrides are logged to the audit trail.
     */
    @PostMapping("/{id}/score-override")
    public ResponseEntity<?> scoreOverride(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        Lead lead = find(id).orElse(null);
        if (lead == null) throw new NoSuchElementException("lead not found");

        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.LEAD,
                                  lead.getOrganizationId(), lead.getOwnerId());
        if (!permissionService.canOverrideLeadScore(actor)) {
            throw new AccessDeniedException("lead score can only be overridden by a Manager or above");
        }

        Object scoreObj  = body.get("score");
        Object reasonObj = body.get("reason");
        if (scoreObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "score is required"));
        }
        if (reasonObj == null || reasonObj.toString().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "reason is required for a score override"));
        }

        int newScore;
        try {
            newScore = Integer.parseInt(scoreObj.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "score must be an integer"));
        }

        Lead before = copy(lead);
        lead.setLeadScore(newScore);
        lead.setLeadScoreOverrideReason(reasonObj.toString());
        lead.setUpdatedAt(Instant.now());
        leadRepository.save(lead);
        auditService.log("update", "leads", lead.getId(), lead.getName(), before,
                         Map.of("score_override", newScore, "reason", reasonObj.toString(),
                                "overridden_by", actor.getId()));
        return ResponseEntity.ok(permissionService.maskLeadForResponse(actor, lead));
    }

    @PostMapping("/{id}/convert")
    public ResponseEntity<?> convert(@PathVariable Long id) {
        Lead lead = find(id).orElse(null);
        if (lead == null) throw new NoSuchElementException("lead not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.LEAD,
                                  lead.getOrganizationId(), lead.getOwnerId());

        if (lead.getConvertedOppId() != null) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "this lead was already converted",
                    "opportunityId", lead.getConvertedOppId()));
        }

        // Ownership semantics: the opportunity belongs to whoever owns the lead.
        // An unowned (pool) lead is first assigned to the converting user, so the
        // converted lead can't later be "claimed" from the pool by someone else
        // and the opp/lead/sourcedBy owners all agree.
        Lead before = copy(lead);
        if (lead.getOwnerId() == null) lead.setOwnerId(actor.getId());

        Opportunity opp = new Opportunity();
        opp.setOrganizationId(currentUser.organizationId());
        opp.setName(lead.getName());
        opp.setCompanyId(lead.getCompanyId());
        opp.setOwnerId(lead.getOwnerId());
        opp.setStage(OpportunityController.STAGES.get(0)); // "Qualification"
        opp.setValue(lead.getValue() != null ? lead.getValue() : 0L);
        opp.setProbability(50);
        opp.setNotes(lead.getNotes());
        opp.setServiceProduct(lead.getRequirement());
        // V5: back-link so Inside Sales can track outcome of their qualified leads
        opp.setSourceLeadId(lead.getId());
        opp.setSourcedByUserId(lead.getOwnerId());
        opp.setCreatedAt(Instant.now());
        opp.setUpdatedAt(Instant.now());
        opportunityRepository.save(opp);

        lead.setStage("Won");
        lead.setConvertedAt(Instant.now());
        lead.setConvertedOppId(opp.getId());
        lead.setUpdatedAt(Instant.now());
        leadRepository.save(lead);

        auditService.log("update", "leads", lead.getId(), lead.getName(), before,
                         Map.of("converted", true, "opportunityId", opp.getId(),
                                "ownerId", lead.getOwnerId()));
        auditService.log("create", "opportunities", opp.getId(), opp.getName(), null, opp);

        // L1: same field masks as every other read — e.g. Inside Sales must not
        // see the new deal's value or the now-handed-off lead's contact details.
        return ResponseEntity.ok(Map.of("ok", true,
                "lead", permissionService.maskLeadForResponse(actor, lead),
                "opportunity", permissionService.maskOpportunityForResponse(actor, opp)));
    }

    @GetMapping("/pipeline")
    public ResponseEntity<?> pipeline(@RequestParam(defaultValue = "stage") String groupBy) {
        if (!PIPELINE_GROUP_BY.contains(groupBy)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "groupBy must be one of " + PIPELINE_GROUP_BY));
        }
        User actor = currentUser.get();
        List<Lead> leads = leadRepository.findByOrganizationId(currentUser.organizationId()).stream()
                .filter(l -> permissionService.can(actor, Action.VIEW, ResourceType.LEAD,
                                                   l.getOrganizationId(), l.getOwnerId()))
                .collect(Collectors.toList());
        // Every column goes through the same contact-info mask as the list endpoint;
        // previously the board view returned raw leads and leaked masked fields.
        Map<String, List<Lead>> columns = permissionService.maskLeadsForResponse(actor, leads).stream()
                .collect(Collectors.groupingBy(l -> keyFor(l, groupBy),
                                               LinkedHashMap::new, Collectors.toList()));
        return ResponseEntity.ok(new PipelineResponse(groupBy, columns));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** {{variable}} context handed to any AiAutomation fired for this lead. */
    private Map<String, String> leadContext(Lead lead) {
        Map<String, String> ctx = new HashMap<>();
        ctx.put("lead.id", String.valueOf(lead.getId()));
        ctx.put("lead.name", nz(lead.getName()));
        ctx.put("lead.email", nz(lead.getEmail()));
        ctx.put("lead.phone", nz(lead.getPhone()));
        ctx.put("lead.stage", nz(lead.getStage()));
        ctx.put("lead.source", nz(lead.getSource()));
        ctx.put("lead.industry", nz(lead.getIndustry()));
        ctx.put("lead.requirement", nz(lead.getRequirement()));
        ctx.put("task", "A lead named " + nz(lead.getName()) + " is now in stage " + nz(lead.getStage()));
        return ctx;
    }

    private String nz(String s) { return s == null ? "" : s; }

    /**
     * Assigning a lead: Manager+ only (Lead.ownerId is read-only below Manager),
     * Managers only within their team ("Assign / reassign leads: Within team"),
     * and the target must be a user of this org.
     */
    private void requireAssignableOwner(User actor, Long ownerId) {
        tenantGuard.requireUser(ownerId);
        if (!permissionService.canEditLeadOwner(actor)) {
            throw new AccessDeniedException("you are not permitted to assign leads to other users");
        }
        if (!permissionService.canAssignTo(actor, ownerId)) {
            throw new AccessDeniedException("you can only assign leads within your team");
        }
    }

    private Optional<Lead> find(Long id) {
        return leadRepository.findByIdAndOrganizationId(id, currentUser.organizationId());
    }

    private void apply(Lead lead, LeadRequest req) {
        if (req.name          != null) lead.setName(req.name);
        if (req.email         != null) lead.setEmail(req.email);
        if (req.phone         != null) lead.setPhone(req.phone);
        if (req.companyId     != null) lead.setCompanyId(req.companyId);
        if (req.ownerId       != null) lead.setOwnerId(req.ownerId);
        if (req.stage         != null) lead.setStage(req.stage);
        if (req.source        != null) lead.setSource(req.source);
        if (req.priority      != null) lead.setPriority(req.priority);
        if (req.value         != null) lead.setValue(req.value);
        if (req.notes         != null) lead.setNotes(req.notes);
        if (req.tags          != null) lead.setTags(req.tags);
        if (req.followUpAt    != null) lead.setFollowUpAt(req.followUpAt);
        if (req.address       != null) lead.setAddress(req.address);
        if (req.businessName  != null) lead.setBusinessName(req.businessName);
        if (req.category      != null) lead.setCategory(req.category);
        if (req.leadType      != null) lead.setLeadType(req.leadType);
        if (req.industry      != null) lead.setIndustry(req.industry);
        if (req.geography     != null) lead.setGeography(req.geography);
        if (req.employeeBand  != null) lead.setEmployeeBand(req.employeeBand);
        if (req.revenueBand   != null) lead.setRevenueBand(req.revenueBand);
        if (req.requirement   != null) lead.setRequirement(req.requirement);
        if (req.painPoint     != null) lead.setPainPoint(req.painPoint);
        if (req.technologyUsed != null) lead.setTechnologyUsed(req.technologyUsed);
        if (req.decisionMaker != null) lead.setDecisionMaker(req.decisionMaker);
        if (req.leadScore     != null) lead.setLeadScore(req.leadScore);
    }

    private String keyFor(Lead lead, String groupBy) {
        String value = switch (groupBy) {
            case "source"   -> lead.getSource();
            case "leadType" -> lead.getLeadType();
            case "category" -> lead.getCategory();
            default         -> lead.getStage();
        };
        return (value == null || value.isBlank()) ? "Uncategorized" : value;
    }

    private Lead copy(Lead l) {
        Lead c = new Lead();
        apply(c, toRequest(l));
        c.setFollowUpRescheduleCount(l.getFollowUpRescheduleCount());
        c.setLeadScoreOverrideReason(l.getLeadScoreOverrideReason());
        return c;
    }

    private LeadRequest toRequest(Lead l) {
        LeadRequest r = new LeadRequest();
        r.name = l.getName(); r.email = l.getEmail(); r.phone = l.getPhone();
        r.companyId = l.getCompanyId(); r.ownerId = l.getOwnerId(); r.stage = l.getStage();
        r.source = l.getSource(); r.priority = l.getPriority(); r.value = l.getValue();
        r.notes = l.getNotes(); r.tags = l.getTags(); r.followUpAt = l.getFollowUpAt();
        r.address = l.getAddress(); r.businessName = l.getBusinessName();
        r.category = l.getCategory(); r.leadType = l.getLeadType();
        r.industry = l.getIndustry(); r.geography = l.getGeography();
        r.employeeBand = l.getEmployeeBand(); r.revenueBand = l.getRevenueBand();
        r.requirement = l.getRequirement(); r.painPoint = l.getPainPoint();
        r.technologyUsed = l.getTechnologyUsed(); r.decisionMaker = l.getDecisionMaker();
        r.leadScore = l.getLeadScore();
        return r;
    }
}
