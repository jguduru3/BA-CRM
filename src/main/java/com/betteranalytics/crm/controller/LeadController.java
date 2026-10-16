package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.CrmMasks;
import com.betteranalytics.crm.core.LeadWorkflowService;
import com.betteranalytics.crm.core.ListParams;
import com.betteranalytics.crm.core.Patch;
import com.betteranalytics.crm.core.PipelineSettingsService;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.dedupe.DuplicateGuard;
import com.betteranalytics.crm.dedupe.ObjectType;
import com.betteranalytics.crm.dedupe.Probe;
import com.betteranalytics.crm.routing.RoutingService;
import com.betteranalytics.crm.dto.PipelineResponse;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AiAutomationService;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.TenantGuard;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private static final Set<String> PIPELINE_GROUP_BY = Set.of("stage", "source", "leadType", "category");
    /** The board view loads at most this many (most recently updated) leads. */
    private static final int PIPELINE_MAX = 1000;

    static final Map<String, String> SORTABLE = Map.ofEntries(
            Map.entry("name", "name"), Map.entry("stage", "stage"), Map.entry("source", "source"),
            Map.entry("priority", "priority"), Map.entry("value", "value"), Map.entry("followUpAt", "followUpAt"),
            Map.entry("leadScore", "leadScore"), Map.entry("createdAt", "createdAt"),
            Map.entry("updatedAt", "updatedAt"), Map.entry("id", "id"));

    private final LeadRepository        leadRepository;
    private final CurrentUser           currentUser;
    private final AuditService          auditService;
    private final PermissionService     permissionService;
    private final NotificationService   notificationService;
    private final AiAutomationService   aiAutomationService;
    private final TenantGuard           tenantGuard;
    private final CrmMasks              masks;
    private final LeadWorkflowService   workflow;
    private final PipelineSettingsService pipelineSettings;
    private final DuplicateGuard        duplicateGuard;
    private final RoutingService        routing;

    public LeadController(LeadRepository leadRepository, CurrentUser currentUser, AuditService auditService,
                          PermissionService permissionService, NotificationService notificationService,
                          AiAutomationService aiAutomationService, TenantGuard tenantGuard, CrmMasks masks,
                          LeadWorkflowService workflow, PipelineSettingsService pipelineSettings,
                          DuplicateGuard duplicateGuard, RoutingService routing) {
        this.leadRepository      = leadRepository;
        this.currentUser         = currentUser;
        this.auditService        = auditService;
        this.permissionService   = permissionService;
        this.notificationService = notificationService;
        this.aiAutomationService = aiAutomationService;
        this.tenantGuard         = tenantGuard;
        this.masks               = masks;
        this.workflow            = workflow;
        this.pipelineSettings    = pipelineSettings;
        this.duplicateGuard      = duplicateGuard;
        this.routing             = routing;
    }

    /**
     * Paginated, filtered and sorted in the database; record scope is part of the
     * WHERE clause. {@code pool=true} lists the unassigned pool (owner IS NULL).
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String search,
                                    @RequestParam(required = false) String stage,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(required = false) String priority,
                                    @RequestParam(required = false) Long ownerId,
                                    @RequestParam(required = false) Long companyId,
                                    @RequestParam(defaultValue = "false") boolean pool,
                                    @RequestParam(defaultValue = "false") boolean overdue,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String sort) {
        User actor = currentUser.get();
        Specification<Lead> spec = visibleSpec(actor)
                .and(searchSpec(actor, search))
                .and(Specs.eqIgnoreCase("stage", stage))
                .and(Specs.eqIgnoreCase("source", source))
                .and(Specs.eqIgnoreCase("priority", priority))
                .and(Specs.eq("ownerId", ownerId))
                .and(Specs.eq("companyId", companyId));
        if (pool) spec = spec.and(Specs.isNull("ownerId"));
        if (overdue) {
            Instant now = Instant.now();
            spec = spec.and((root, q, cb) -> cb.and(cb.lessThan(root.get("followUpAt"), now),
                    cb.not(root.get("stage").in("Won", "Lost"))));
        }
        Pageable pageable = ListParams.pageable(page, size, limit, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Lead> result = leadRepository.findAll(spec, pageable);
        return ListParams.response(result, masks.leads(actor, result.getContent()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Lead> get(@PathVariable Long id) {
        Lead lead = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.LEAD, lead.getOrganizationId(), lead.getOwnerId());
        return ResponseEntity.ok(masks.lead(actor, lead));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody JsonNode body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.LEAD, currentUser.organizationId(), null);
        Patch p = Patch.of(body);
        if (!p.hasValue("name")) throw new IllegalArgumentException("name is required");
        Long ownerId = p.lng("ownerId");
        if (ownerId != null && !ownerId.equals(actor.getId())) requireAssignableOwner(actor, ownerId);
        tenantGuard.requireCompany(p.lng("companyId"));

        Lead lead = new Lead();
        apply(lead, p, actor, true);
        // Lead score is system-computed; only Manager+ may set it (spec §3). The create
        // form sends the default score, so for other roles it is ignored, not rejected.
        if (p.has("leadScore") && permissionService.canOverrideLeadScore(actor)) {
            Integer score = p.integer("leadScore");
            if (score != null) lead.setLeadScore(score);
        }
        lead.setOrganizationId(currentUser.organizationId());
        // Duplicate rules first: a rejected lead must not consume a routing turn.
        DuplicateGuard.Outcome duplicates = duplicateGuard.checkCreate(actor, ObjectType.LEAD, Probe.of(lead));
        // Round-robin routing: unowned leads from the API, or from a Manager+ who left the owner empty.
        User routedTo = ownerId == null && routing.applies(actor)
                ? routing.nextAssignee(lead.getOrganizationId(), lead.getGeography()).orElse(null)
                : null;
        lead.setOwnerId(ownerId != null ? ownerId : routedTo != null ? routedTo.getId() : actor.getId());
        lead.setFollowUpRescheduleCount(0);
        lead.setCreatedAt(Instant.now());
        lead.setUpdatedAt(Instant.now());
        leadRepository.save(lead);
        auditService.log("create", "leads", lead.getId(), lead.getName(), null, lead);
        duplicateGuard.afterSave(duplicates, lead.getId(), lead.getName());
        if (routedTo != null) {
            Map<String, Object> assigned = new LinkedHashMap<>();
            assigned.put("ownerId", routedTo.getId());
            assigned.put("ownerName", routedTo.getName());
            assigned.put("rule", "round_robin");
            auditService.log("auto_assign", "leads", lead.getId(), lead.getName(), Map.of("ownerId", "unassigned"), assigned);
            notificationService.notify(actor.getOrganizationId(), routedTo.getId(),
                    "lead_assigned", "New lead assigned to you: " + lead.getName(),
                    "Auto-assigned by round-robin lead routing", "Lead", lead.getId());
        } else if (!lead.getOwnerId().equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), lead.getOwnerId(),
                    "lead_assigned", "Lead assigned to you: " + lead.getName(),
                    "Assigned by " + actor.getName(), "Lead", lead.getId());
        }
        aiAutomationService.fireEvent(lead.getOrganizationId(), "on_lead_created", leadContext(lead));
        return ResponseEntity.status(201).body(masks.lead(actor, lead));
    }

    /**
     * Partial update: only fields present in the body change (PUT and PATCH behave
     * the same). Send the {@code version} you loaded to get 409 STALE_UPDATE instead
     * of silently overwriting someone else's change.
     */
    @Transactional
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody JsonNode body) {
        Lead existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.LEAD, existing.getOrganizationId(), existing.getOwnerId());
        Patch p = Patch.of(body);
        requireCurrentVersion(p, existing.getVersion(), id);

        // Field-mask enforcement (step 4 of PermissionService's fixed order)
        if (p.hasValue("source") && !p.str("source").equals(existing.getSource())
                && !permissionService.canEditLeadSource(actor, false)) {
            throw new AccessDeniedException("lead source is locked after creation for your role");
        }
        if (p.has("ownerId") && !Objects.equals(p.lng("ownerId"), existing.getOwnerId())) {
            Long newOwner = p.lng("ownerId");
            if (newOwner == null) {
                if (!permissionService.canEditLeadOwner(actor)) {
                    throw new AccessDeniedException("you are not permitted to unassign leads");
                }
            } else {
                requireAssignableOwner(actor, newOwner);
            }
        }
        if (p.has("leadScore") && p.integer("leadScore") != null
                && !p.integer("leadScore").equals(existing.getLeadScore())) {
            // Changing the score through a plain edit would bypass the audited override flow.
            if (!permissionService.canOverrideLeadScore(actor)) {
                throw new AccessDeniedException("lead score can only be overridden by a Manager or above");
            }
            throw new IllegalArgumentException("lead score changes need a reason: use POST /api/leads/{id}/score-override");
        }
        if (p.has("name") && !p.hasValue("name")) throw new IllegalArgumentException("name cannot be blank");
        if (p.has("companyId") && !Objects.equals(p.lng("companyId"), existing.getCompanyId())) {
            tenantGuard.requireCompany(p.lng("companyId"));
        }
        if (p.has("geography") && !Objects.equals(blankToNull(p.str("geography")), existing.getGeography())
                && !permissionService.canEditLeadGeography(actor)) {
            throw new AccessDeniedException("geography determines territory routing; request the change via your Manager");
        }

        Lead before = snapshot(existing);
        Probe keysBefore = Probe.of(existing);
        Long previousOwnerId = existing.getOwnerId();
        String previousStage = existing.getStage();
        Instant previousFollowUp = existing.getFollowUpAt();

        apply(existing, p, actor, false);
        if (p.has("ownerId")) existing.setOwnerId(p.lng("ownerId"));
        DuplicateGuard.Outcome duplicates =
                duplicateGuard.checkEdit(actor, ObjectType.LEAD, keysBefore, Probe.of(existing));

        if (!Objects.equals(previousFollowUp, existing.getFollowUpAt())) {
            // Only a change of an already-set date counts as a reschedule (spec §4).
            if (previousFollowUp != null && existing.getFollowUpAt() != null) {
                existing.setFollowUpRescheduleCount(
                        (existing.getFollowUpRescheduleCount() == null ? 0 : existing.getFollowUpRescheduleCount()) + 1);
            }
            existing.setFollowUpNotifiedAt(null); // re-arm the overdue reminder for the new date
        }
        existing.setUpdatedAt(Instant.now());
        leadRepository.saveAndFlush(existing);
        auditService.log("update", "leads", existing.getId(), existing.getName(), before, existing);
        duplicateGuard.afterSave(duplicates, existing.getId(), existing.getName());

        if (existing.getStage() != null && !existing.getStage().equals(previousStage)) {
            aiAutomationService.fireEvent(existing.getOrganizationId(), "on_stage_change", leadContext(existing));
        }
        Long newOwnerId = existing.getOwnerId();
        if (newOwnerId != null && !newOwnerId.equals(previousOwnerId) && !newOwnerId.equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), newOwnerId,
                    "lead_assigned", "Lead assigned to you: " + existing.getName(),
                    "Reassigned by " + actor.getName(), "Lead", existing.getId());
        }
        return ResponseEntity.ok(masks.lead(actor, existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Lead existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.LEAD, existing.getOrganizationId(), existing.getOwnerId());
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
        Lead existing = leadRepository.findByIdAndOrganizationIdIncludingDeleted(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("lead not found"));
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.LEAD, existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(null);
        existing.setDeletedBy(null);
        existing.setDeleteReason(null);
        leadRepository.save(existing);
        auditService.log("update", "leads", id, existing.getName(), null, Map.of("restored", true));
        return ResponseEntity.ok(masks.lead(actor, existing));
    }

    @GetMapping("/trash")
    public List<Lead> trash() {
        User actor = currentUser.get();
        return leadRepository.findTrashedByOrganizationId(currentUser.organizationId()).stream()
                .filter(l -> permissionService.can(actor, Action.DELETE, ResourceType.LEAD,
                                                   l.getOrganizationId(), l.getOwnerId()))
                .map(l -> masks.lead(actor, l))
                .collect(Collectors.toList());
    }

    /**
     * Claim a lead from the unassigned pool. Spec §2: Executive and Inside Sales may
     * claim; Field Sales only inside their territory. Atomic — see LeadWorkflowService.
     */
    @PostMapping("/{id}/claim")
    public ResponseEntity<?> claim(@PathVariable Long id) {
        Lead lead = find(id);
        User actor = currentUser.get();
        Role role = actor.getRole();
        if (role != Role.EXECUTIVE && role != Role.INSIDE_SALES && role != Role.FIELD_SALES) {
            throw new AccessDeniedException("use the owner field (assign) to give leads to people for your role");
        }
        if (lead.getOwnerId() != null) {
            throw new IllegalStateException("this lead has already been claimed by someone else");
        }
        if (role == Role.FIELD_SALES) {
            String territory = actor.getTerritory();
            String geography = lead.getGeography();
            if (territory == null || geography == null
                    || !geography.toLowerCase().contains(territory.toLowerCase())) {
                throw new AccessDeniedException("lead geography does not match your territory");
            }
        }
        Lead claimed = withLockRetry(() -> workflow.claim(actor, lead));
        return ResponseEntity.ok(masks.lead(actor, claimed));
    }

    /**
     * Manager override of the lead score with a mandatory reason (spec §3). The
     * reason is stored on the lead and recorded in the audit log.
     */
    @PostMapping("/{id}/score-override")
    public ResponseEntity<?> scoreOverride(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Lead lead = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.LEAD, lead.getOrganizationId(), lead.getOwnerId());
        if (!permissionService.canOverrideLeadScore(actor)) {
            throw new AccessDeniedException("lead score can only be overridden by a Manager or above");
        }
        Object scoreObj  = body.get("score");
        Object reasonObj = body.get("reason");
        if (scoreObj == null) throw new IllegalArgumentException("score is required");
        if (reasonObj == null || reasonObj.toString().isBlank()) {
            throw new IllegalArgumentException("reason is required for a score override");
        }
        int newScore;
        try {
            newScore = Integer.parseInt(scoreObj.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("score must be an integer");
        }
        if (newScore < 0 || newScore > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        String reason = reasonObj.toString().trim();
        if (reason.length() > 500) reason = reason.substring(0, 500);

        Lead before = snapshot(lead);
        lead.setLeadScore(newScore);
        lead.setLeadScoreOverrideReason(reason);
        lead.setUpdatedAt(Instant.now());
        leadRepository.save(lead);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("score_override", newScore);
        after.put("previous_score", before.getLeadScore());
        after.put("reason", reason);
        after.put("overridden_by", actor.getId());
        auditService.log("update", "leads", lead.getId(), lead.getName(), before, after);
        return ResponseEntity.ok(masks.lead(actor, leadRepository.findById(lead.getId()).orElse(lead)));
    }

    /**
     * Convert to an opportunity. Idempotent: a repeated or concurrent call returns
     * the same opportunity (200), never a second one.
     */
    @PostMapping("/{id}/convert")
    public ResponseEntity<?> convert(@PathVariable Long id) {
        Lead lead = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.LEAD, lead.getOrganizationId(), lead.getOwnerId());
        LeadWorkflowService.Conversion c = withLockRetry(() -> workflow.convert(actor, id));
        if (c.created() && pipelineSettings.isBigDeal(actor.getOrganizationId(), c.opportunity())) {
            pipelineSettings.alertBigDeal(actor, c.opportunity(), "created");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("created", c.created());
        out.put("lead", masks.lead(actor, c.lead()));
        out.put("opportunity", masks.opportunity(actor, c.opportunity()));
        out.put("contactId", c.contact() == null ? null : c.contact().getId());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/pipeline")
    public ResponseEntity<?> pipeline(@RequestParam(defaultValue = "stage") String groupBy) {
        if (!PIPELINE_GROUP_BY.contains(groupBy)) {
            throw new IllegalArgumentException("groupBy must be one of " + PIPELINE_GROUP_BY);
        }
        User actor = currentUser.get();
        List<Lead> leads = leadRepository.findAll(visibleSpec(actor),
                PageRequest.of(0, PIPELINE_MAX, Sort.by(Sort.Direction.DESC, "updatedAt"))).getContent();
        Map<String, List<Lead>> columns = masks.leads(actor, leads).stream()
                .collect(Collectors.groupingBy(l -> keyFor(l, groupBy), LinkedHashMap::new, Collectors.toList()));
        return ResponseEntity.ok(new PipelineResponse(groupBy, columns));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Organisation + record scope as a query predicate; 403 for roles with no lead access. */
    private Specification<Lead> visibleSpec(User actor) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.LEAD);
        if (scope.none()) throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW leads");
        return Specs.<Lead>org(actor.getOrganizationId()).and(Specs.ownedWithin(scope, "ownerId"));
    }

    /**
     * Name / business name / email search. For users who see handed-off leads with
     * masked contact details (Inside Sales), the email only matches on leads whose
     * contact info they may still read — otherwise search would be an oracle.
     */
    private Specification<Lead> searchSpec(User actor, String search) {
        if (search == null || search.isBlank()) return Specs.search(null);
        String pattern = ListParams.likePattern(search);
        boolean maskedAfterHandOff = actor.getRole() == Role.INSIDE_SALES;
        return (root, q, cb) -> {
            List<Predicate> any = new ArrayList<>();
            any.add(cb.like(cb.lower(root.get("name")), pattern, '\\'));
            any.add(cb.like(cb.lower(root.get("businessName")), pattern, '\\'));
            Predicate email = cb.like(cb.lower(root.get("email")), pattern, '\\');
            any.add(maskedAfterHandOff ? cb.and(email, cb.isNull(root.get("convertedOppId"))) : email);
            return cb.or(any.toArray(Predicate[]::new));
        };
    }

    /**
     * Claim and convert are idempotent / conditional, so when the database gives up
     * waiting for a row lock held by a concurrent call (lock timeout) or the unique
     * index catches a race, simply running them again yields the correct answer.
     */
    private static <T> T withLockRetry(java.util.function.Supplier<T> op) {
        for (int attempt = 1; ; attempt++) {
            try {
                return op.get();
            } catch (org.springframework.dao.PessimisticLockingFailureException
                     | org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt >= 3) throw e;
            }
        }
    }

    private void requireCurrentVersion(Patch p, Long current, Long id) {
        if (!p.has("version")) return;
        Long sent = p.lng("version");
        if (sent != null && !sent.equals(current)) {
            throw new ObjectOptimisticLockingFailureException(Lead.class, id);
        }
    }

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

    private static String nz(String s) { return s == null ? "" : s; }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    /**
     * Assigning a lead: Manager+ only (Lead.ownerId is read-only below Manager),
     * Managers only within their team, and the target must be a user of this org.
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

    private Lead find(Long id) {
        return leadRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("lead not found"));
    }

    /**
     * Applies the client-editable fields present in the body. Owner, score, version and
     * every server-managed column are handled by the caller. Contact details the actor
     * only sees masked are ignored, so saving a form never writes "••••" over them.
     */
    private void apply(Lead lead, Patch p, User actor, boolean creating) {
        p.requiredText("name", lead::setName);
        boolean contactVisible = creating || permissionService.canViewLeadContactInfo(actor, lead);
        if (contactVisible) {
            if (!CrmMasks.isMask(p.str("email"))) p.text("email", lead::setEmail);
            if (!CrmMasks.isMask(p.str("phone"))) p.text("phone", lead::setPhone);
        }
        p.longValue("companyId", lead::setCompanyId);
        p.requiredText("stage", lead::setStage);
        p.requiredText("source", lead::setSource);
        p.requiredText("priority", lead::setPriority);
        p.longValue("value", lead::setValue);
        p.text("notes", lead::setNotes);
        p.text("tags", lead::setTags);
        p.instantValue("followUpAt", lead::setFollowUpAt);
        p.text("address", lead::setAddress);
        p.text("businessName", lead::setBusinessName);
        p.text("category", lead::setCategory);
        p.text("leadType", lead::setLeadType);
        p.text("industry", lead::setIndustry);
        p.text("geography", lead::setGeography);
        p.text("employeeBand", lead::setEmployeeBand);
        p.text("revenueBand", lead::setRevenueBand);
        p.text("requirement", lead::setRequirement);
        p.text("painPoint", lead::setPainPoint);
        p.text("technologyUsed", lead::setTechnologyUsed);
        p.text("decisionMaker", lead::setDecisionMaker);
        if (lead.getValue() != null && lead.getValue() < 0) throw new IllegalArgumentException("value must not be negative");
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

    private static Lead snapshot(Lead l) {
        Lead c = new Lead();
        BeanUtils.copyProperties(l, c);
        return c;
    }
}
