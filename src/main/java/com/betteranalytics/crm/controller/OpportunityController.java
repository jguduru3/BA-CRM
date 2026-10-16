package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.CrmMasks;
import com.betteranalytics.crm.core.ListParams;
import com.betteranalytics.crm.core.Patch;
import com.betteranalytics.crm.core.PipelineSettingsService;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.entity.ForecastCategory;
import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.OpportunityStageHistory;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.pipeline.DealHealthService;
import com.betteranalytics.crm.pipeline.StageHistoryService;
import com.betteranalytics.crm.repository.OpportunityStageHistoryRepository;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.OpportunityRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AiAutomationService;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.TenantGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/opportunities")
public class OpportunityController {

    /**
     * Valid pipeline stages — must match OPP_STAGES in the frontend's
     * OpportunityForm.tsx and the "Qualification" stage lead conversion creates.
     */
    static final List<String> STAGES = PipelineSettingsService.STAGES;

    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name", "stage", "stage", "value", "value", "probability", "probability",
            "closeDate", "closeDate", "createdAt", "createdAt", "updatedAt", "updatedAt", "id", "id");
    /** Sort keys that would leak hidden values through ordering for roles that can't see them. */
    private static final Set<String> VALUE_SORTS = Set.of("value", "probability");

    private final OpportunityRepository opportunityRepository;
    private final CurrentUser           currentUser;
    private final AuditService          auditService;
    private final PermissionService     permissionService;
    private final NotificationService   notificationService;
    private final AiAutomationService   aiAutomationService;
    private final TenantGuard           tenantGuard;
    private final CrmMasks              masks;
    private final PipelineSettingsService pipelineSettings;
    private final StageHistoryService     stageHistory;
    private final OpportunityStageHistoryRepository historyRepository;
    private final DealHealthService       dealHealth;

    public OpportunityController(OpportunityRepository opportunityRepository, CurrentUser currentUser,
                                 AuditService auditService, PermissionService permissionService,
                                 NotificationService notificationService, AiAutomationService aiAutomationService,
                                 TenantGuard tenantGuard, CrmMasks masks, PipelineSettingsService pipelineSettings,
                                 StageHistoryService stageHistory, OpportunityStageHistoryRepository historyRepository,
                                 DealHealthService dealHealth) {
        this.stageHistory          = stageHistory;
        this.historyRepository     = historyRepository;
        this.dealHealth            = dealHealth;
        this.opportunityRepository = opportunityRepository;
        this.currentUser           = currentUser;
        this.auditService          = auditService;
        this.permissionService     = permissionService;
        this.notificationService   = notificationService;
        this.aiAutomationService   = aiAutomationService;
        this.tenantGuard           = tenantGuard;
        this.masks                 = masks;
        this.pipelineSettings      = pipelineSettings;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String search,
                                    @RequestParam(required = false) String stage,
                                    @RequestParam(required = false) Long ownerId,
                                    @RequestParam(required = false) Long companyId,
                                    @RequestParam(required = false) Long contactId,
                                    @RequestParam(defaultValue = "false") boolean open,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String sort) {
        User actor = currentUser.get();
        Specification<Opportunity> spec = visibleSpec(actor)
                .and(Specs.search(search, "name", "serviceProduct"))
                .and(Specs.eqIgnoreCase("stage", stage))
                .and(Specs.eq("ownerId", ownerId))
                .and(Specs.eq("companyId", companyId))
                .and(Specs.eq("contactId", contactId));
        if (open) spec = spec.and((root, q, cb) -> cb.not(root.get("stage").in(PipelineSettingsService.CLOSED_STAGES)));
        Map<String, String> sortable = SORTABLE;
        if (!permissionService.canViewOpportunityValue(actor)) {
            sortable = new HashMap<>(SORTABLE);
            VALUE_SORTS.forEach(sortable::remove);
        }
        Pageable pageable = ListParams.pageable(page, size, limit, sort, sortable, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Opportunity> result = opportunityRepository.findAll(spec, pageable);
        List<Opportunity> data = result.getContent().stream().map(o -> forResponse(actor, o)).toList();
        return ListParams.response(result, data);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Opportunity> get(@PathVariable Long id) {
        Opportunity opp = find(id);
        User actor = currentUser.get();
        boolean standard = permissionService.can(actor, Action.VIEW, ResourceType.OPPORTUNITY,
                opp.getOrganizationId(), opp.getOwnerId());
        if (!standard && !permissionService.canViewSourcedOpportunity(actor, opp)) {
            throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW this OPPORTUNITY");
        }
        return ResponseEntity.ok(forResponse(actor, opp));
    }

    /** Append-only stage history of a deal, oldest first. Visible to whoever may view the deal. */
    @GetMapping("/{id}/stage-history")
    public List<OpportunityStageHistory> stageHistory(@PathVariable Long id) {
        get(id); // same visibility rules as the deal itself
        return historyRepository.findByOpportunityIdAndOrganizationIdOrderByEnteredAtAscIdAsc(id, currentUser.organizationId());
    }

    @Transactional
    @PostMapping
    public ResponseEntity<?> create(@RequestBody JsonNode body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.OPPORTUNITY, currentUser.organizationId(), null);
        Patch p = Patch.of(body);
        if (!p.hasValue("name")) throw new IllegalArgumentException("name is required");
        Long ownerId = p.lng("ownerId");
        if (ownerId != null && !ownerId.equals(actor.getId())) requireAssignableOwner(actor, ownerId);
        String stage = p.hasValue("stage") ? p.str("stage") : STAGES.get(0);
        validate(stage, p.integer("probability"), p.lng("value"));
        if (p.hasValue("poStatus") && !permissionService.canEditOpportunityPoStatus(actor)) {
            throw new AccessDeniedException("payment/PO status can only be set by a Manager or above");
        }
        tenantGuard.requireCompany(p.lng("companyId"));
        tenantGuard.requireContact(p.lng("contactId"));

        Opportunity opp = new Opportunity();
        apply(opp, p);
        opp.setStage(stage);
        opp.setOrganizationId(currentUser.organizationId());
        opp.setOwnerId(ownerId != null ? ownerId : actor.getId());
        if (opp.getValue() == null) opp.setValue(0L); // column is NOT NULL

        // Probability is stage-driven (organisation defaults). Only Manager+ may set it
        // by hand, which marks the deal as overridden. The old form always sent 50,
        // so a non-Manager sending 50 or the stage default is accepted and ignored.
        int stageDefault = pipelineSettings.get(opp.getOrganizationId()).probabilityFor(stage);
        Integer requested = p.integer("probability");
        opp.setProbability(stageDefault);
        if (requested != null && requested != stageDefault) {
            if (permissionService.canEditOpportunityProbability(actor)) {
                opp.setProbability(requested);
                opp.setProbabilityOverridden(true);
            } else if (requested != 50) {
                throw new AccessDeniedException("probability is stage-driven and not directly editable for your role");
            }
        }
        applyCloseTransition(opp, null, p);
        Instant now = Instant.now();
        opp.setCreatedAt(now);
        opp.setUpdatedAt(now);
        opp.setStageEnteredAt(now);
        if (p.hasValue("forecastCategory")) {
            opp.setForecastCategory(validCategoryOverride(opp.getStage(), p.str("forecastCategory")));
            opp.setForecastCategoryOverridden(true);
        }
        pipelineSettings.applyNewDealDefaults(opp);
        DealHealthService.Health health = dealHealth.evaluate(opp, now, dealHealth.benchmarks(opp.getOrganizationId()));
        dealHealth.apply(opp, health, now);
        opportunityRepository.save(opp);
        pipelineSettings.recordNewDeal(opp, actor.getId());
        auditService.log("create", "opportunities", opp.getId(), opp.getName(), null, opp);
        dealHealth.notifyRaised(opp, Set.of(), health, actor.getId());
        if (pipelineSettings.isBigDeal(opp.getOrganizationId(), opp)) {
            pipelineSettings.alertBigDeal(actor, opp, "created");
        }
        return ResponseEntity.status(201).body(masks.opportunity(actor, opp));
    }

    /**
     * Partial update (PUT and PATCH): only fields present in the body change.
     * Moving to Won/Lost requires {@code closeReason}. Send {@code version} to get
     * 409 STALE_UPDATE instead of overwriting a concurrent change.
     */
    @Transactional
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody JsonNode body) {
        Opportunity existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.OPPORTUNITY,
                existing.getOrganizationId(), existing.getOwnerId());
        Patch p = Patch.of(body);
        if (p.has("version") && p.lng("version") != null && !p.lng("version").equals(existing.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Opportunity.class, id);
        }

        Integer requestedProbability = p.integer("probability");
        boolean probabilityChanging = requestedProbability != null && !requestedProbability.equals(existing.getProbability());
        if (probabilityChanging && !permissionService.canEditOpportunityProbability(actor)) {
            throw new AccessDeniedException("probability is stage-driven and not directly editable for your role");
        }
        if (p.has("probabilityOverridden") && !Objects.equals(p.bool("probabilityOverridden"), existing.isProbabilityOverridden())
                && !permissionService.canEditOpportunityProbability(actor)) {
            throw new AccessDeniedException("probability is stage-driven and not directly editable for your role");
        }
        if (p.has("poStatus") && !Objects.equals(blankToNull(p.str("poStatus")), existing.getPoStatus())
                && !permissionService.canEditOpportunityPoStatus(actor)) {
            throw new AccessDeniedException("payment/PO status can only be set by a Manager or above");
        }
        Long newOwner = p.lng("ownerId");
        if (newOwner != null && !newOwner.equals(existing.getOwnerId())) requireAssignableOwner(actor, newOwner);
        validate(p.hasValue("stage") ? p.str("stage") : null, requestedProbability, p.lng("value"));
        if (p.has("name") && !p.hasValue("name")) throw new IllegalArgumentException("name cannot be blank");
        if (p.has("companyId") && !Objects.equals(p.lng("companyId"), existing.getCompanyId())) {
            tenantGuard.requireCompany(p.lng("companyId"));
        }
        if (p.has("contactId") && !Objects.equals(p.lng("contactId"), existing.getContactId())) {
            tenantGuard.requireContact(p.lng("contactId"));
        }

        ForecastCategory requestedCategory = p.hasValue("forecastCategory") ? ForecastCategory.parse(p.str("forecastCategory")) : null;

        Opportunity before = snapshot(existing);
        Set<DealHealthService.Flag> previousFlags = DealHealthService.parseFlags(existing.getHealthFlags());
        apply(existing, p);
        if (!permissionService.canViewOpportunityValue(actor)) existing.setValue(before.getValue());
        if (newOwner != null) existing.setOwnerId(newOwner);
        if (p.hasValue("stage")) existing.setStage(p.str("stage"));
        // a close date pushed later counts as a slip (pulling it in does not)
        if (before.getCloseDate() != null && existing.getCloseDate() != null
                && existing.getCloseDate().isAfter(before.getCloseDate())) {
            existing.setCloseDateSlips(existing.getCloseDateSlips() + 1);
        }

        // Probability: a manual value marks the deal overridden; clearing the flag
        // (or a stage change on a non-overridden deal) applies the stage default.
        if (probabilityChanging) {
            existing.setProbability(requestedProbability);
            existing.setProbabilityOverridden(true);
        } else if (p.has("probabilityOverridden") && Boolean.FALSE.equals(p.bool("probabilityOverridden"))) {
            existing.setProbabilityOverridden(false);
        }
        boolean stageChanged = !Objects.equals(existing.getStage(), before.getStage());
        if (!existing.isProbabilityOverridden() && (stageChanged || before.isProbabilityOverridden())) {
            existing.setProbability(pipelineSettings.get(existing.getOrganizationId()).probabilityFor(existing.getStage()));
        }
        applyCloseTransition(existing, before.getStage(), p);
        applyForecastCategory(existing, stageChanged, requestedCategory, p);
        Instant now = Instant.now();
        if (stageChanged) existing.setStageEnteredAt(now);
        DealHealthService.Health health = dealHealth.evaluate(existing, now, dealHealth.benchmarks(existing.getOrganizationId()));
        dealHealth.apply(existing, health, now);
        existing.setUpdatedAt(now);
        opportunityRepository.saveAndFlush(existing);
        if (stageChanged) {
            stageHistory.recordChange(existing, before.getStage(), before.getStageEnteredAt(), actor.getId(), now);
        }
        auditService.log("update", "opportunities", id, existing.getName(), before, existing);
        dealHealth.notifyRaised(existing, previousFlags, health, actor.getId());

        String newStage = existing.getStage();
        if (stageChanged && existing.getOwnerId() != null && !existing.getOwnerId().equals(actor.getId())) {
            notificationService.notifyOppStageChanged(actor.getOrganizationId(),
                    existing.getOwnerId(), existing.getName(), newStage, existing.getId());
        }
        if (stageChanged) {
            if (pipelineSettings.isBigDeal(existing.getOrganizationId(), existing)) {
                pipelineSettings.alertBigDeal(actor, existing, "moved to " + newStage);
            }
            Map<String, String> ctx = new HashMap<>();
            ctx.put("opportunity.id", String.valueOf(existing.getId()));
            ctx.put("opportunity.name", existing.getName());
            ctx.put("opportunity.stage", newStage);
            ctx.put("opportunity.value", String.valueOf(existing.getValue()));
            ctx.put("task", "Opportunity \"" + existing.getName() + "\" moved to stage " + newStage);
            aiAutomationService.fireEvent(actor.getOrganizationId(), "on_stage_change", ctx);
        }
        return ResponseEntity.ok(masks.opportunity(actor, existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Opportunity existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.OPPORTUNITY,
                existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(currentUser.userId());
        existing.setDeleteReason(reason);
        opportunityRepository.save(existing);
        auditService.log("delete", "opportunities", id, existing.getName(), existing,
                         Map.of("reason", reason == null ? "" : reason));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id) {
        Opportunity existing = opportunityRepository
                .findByIdAndOrganizationIdIncludingDeleted(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("opportunity not found"));
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.OPPORTUNITY,
                existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(null);
        existing.setDeletedBy(null);
        existing.setDeleteReason(null);
        opportunityRepository.save(existing);
        auditService.log("update", "opportunities", id, existing.getName(), null, Map.of("restored", true));
        return ResponseEntity.ok(masks.opportunity(actor, existing));
    }

    @GetMapping("/trash")
    public List<Opportunity> trash() {
        User actor = currentUser.get();
        return opportunityRepository.findTrashedByOrganizationId(currentUser.organizationId()).stream()
                .filter(o -> permissionService.can(actor, Action.DELETE, ResourceType.OPPORTUNITY,
                                                   o.getOrganizationId(), o.getOwnerId()))
                .map(o -> masks.opportunity(actor, o))
                .collect(Collectors.toList());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Org + record scope in SQL. Inside Sales additionally sees (stage-only) the deals
     * sourced from leads they qualified (spec §5).
     */
    Specification<Opportunity> visibleSpec(User actor) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.OPPORTUNITY);
        boolean sourced = actor.getRole() == Role.INSIDE_SALES;
        if (scope.none() && !sourced) {
            throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW opportunities");
        }
        Specification<Opportunity> owned = Specs.ownedWithin(scope, "ownerId");
        if (sourced) owned = owned.or(Specs.eq("sourcedByUserId", actor.getId()));
        return Specs.<Opportunity>org(actor.getOrganizationId()).and(owned);
    }

    private Opportunity forResponse(User actor, Opportunity o) {
        boolean standard = permissionService.can(actor, Action.VIEW, ResourceType.OPPORTUNITY,
                o.getOrganizationId(), o.getOwnerId());
        return standard ? masks.opportunity(actor, o) : masks.sourcedOnly(o);
    }

    /**
     * Won/Lost need a reason (stored with the time the deal closed); re-opening a
     * deal clears both.
     */
    private void applyCloseTransition(Opportunity opp, String previousStage, Patch p) {
        boolean closedNow = PipelineSettingsService.CLOSED_STAGES.contains(opp.getStage());
        boolean wasClosed = previousStage != null && PipelineSettingsService.CLOSED_STAGES.contains(previousStage);
        boolean stageChanged = !Objects.equals(opp.getStage(), previousStage);
        if (closedNow && stageChanged) {
            String reason = p.hasValue("closeReason") ? p.str("closeReason").trim() : null;
            if (reason == null) {
                throw new IllegalArgumentException("closeReason is required when marking a deal " + opp.getStage());
            }
            opp.setCloseReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
            opp.setClosedAt(Instant.now());
        } else if (!closedNow && wasClosed) {
            opp.setCloseReason(null);
            opp.setClosedAt(null);
        } else if (closedNow && p.hasValue("closeReason")) {
            String reason = p.str("closeReason").trim();
            opp.setCloseReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
        }
    }

    /**
     * Forecast category (HubSpot model): synced from the org's stage mapping on
     * every stage change unless the rep overrode it. Closing a deal always applies
     * the closed stage's category and clears the override; sending
     * {@code forecastCategoryOverridden: false} goes back to the mapping.
     */
    private void applyForecastCategory(Opportunity opp, boolean stageChanged, ForecastCategory requested, Patch p) {
        boolean closed = PipelineSettingsService.CLOSED_STAGES.contains(opp.getStage());
        ForecastCategory mapped = pipelineSettings.get(opp.getOrganizationId()).categoryFor(opp.getStage());
        if (requested != null && requested != opp.getForecastCategory()) {
            opp.setForecastCategory(validCategoryOverride(opp.getStage(), requested.name()));
            opp.setForecastCategoryOverridden(true);
        } else if (p.has("forecastCategoryOverridden") && Boolean.FALSE.equals(p.bool("forecastCategoryOverridden"))) {
            opp.setForecastCategoryOverridden(false);
        }
        if (closed && (stageChanged || opp.isForecastCategoryOverridden())) {
            if (requested != null && requested != mapped) {
                throw new IllegalArgumentException("a " + opp.getStage() + " deal is always " + mapped);
            }
            opp.setForecastCategoryOverridden(false);
        }
        if (!opp.isForecastCategoryOverridden()) opp.setForecastCategory(mapped);
    }

    /** A rep override: open deals only, and never CLOSED_WON (reserved for won deals). */
    private static ForecastCategory validCategoryOverride(String stage, String value) {
        ForecastCategory c = ForecastCategory.parse(value);
        if (PipelineSettingsService.CLOSED_STAGES.contains(stage)) {
            throw new IllegalArgumentException("the forecast category of a " + stage + " deal cannot be overridden");
        }
        if (c == ForecastCategory.CLOSED_WON) throw new IllegalArgumentException("only Won deals can be CLOSED_WON");
        return c;
    }

    /** Client-editable fields; stage, owner, probability and server columns are handled by the caller. */
    private void apply(Opportunity o, Patch p) {
        p.requiredText("name", o::setName);
        p.longValue("companyId", o::setCompanyId);
        p.longValue("contactId", o::setContactId);
        if (p.has("value")) {
            Long v = p.lng("value");
            o.setValue(v == null ? 0L : v);
        }
        p.dateValue("closeDate", o::setCloseDate);
        p.text("notes", o::setNotes);
        p.text("serviceProduct", o::setServiceProduct);
        p.text("competitors", o::setCompetitors);
        p.text("currentVendor", o::setCurrentVendor);
        p.text("proposalStatus", o::setProposalStatus);
        p.text("negotiationStatus", o::setNegotiationStatus);
        p.text("poStatus", o::setPoStatus);
    }

    /**
     * H11: the new owner must be a user in this org, the actor must be allowed to
     * assign at all (Manager+), and a Manager may only assign within their team.
     */
    private void requireAssignableOwner(User actor, Long ownerId) {
        tenantGuard.requireUser(ownerId);
        if (!permissionService.canEditLeadOwner(actor) || !permissionService.canAssignTo(actor, ownerId)) {
            throw new AccessDeniedException("you are not permitted to assign this opportunity to that user");
        }
    }

    /** Stage must be a known pipeline stage, probability 0..100, value non-negative. */
    static void validate(String stage, Integer probability, Long value) {
        String invalid = validateFields(stage, probability, value);
        if (invalid != null) throw new IllegalArgumentException(invalid);
    }

    static String validateFields(String stage, Integer probability, Long value) {
        if (stage != null && !STAGES.contains(stage)) return "stage must be one of " + STAGES;
        if (probability != null && (probability < 0 || probability > 100)) return "probability must be between 0 and 100";
        if (value != null && value < 0) return "value must not be negative";
        return null;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private Opportunity find(Long id) {
        return opportunityRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("opportunity not found"));
    }

    private static Opportunity snapshot(Opportunity o) {
        Opportunity c = new Opportunity();
        BeanUtils.copyProperties(o, c);
        return c;
    }
}
