package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.OpportunityRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AiAutomationService;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/opportunities")
public class OpportunityController {

    /**
     * Valid pipeline stages — must match OPP_STAGES in the frontend's
     * OpportunityForm.tsx, ReportsController.pipeline() and the "Qualification"
     * stage LeadController.convert() creates (M14).
     */
    static final List<String> STAGES =
            List.of("Qualification", "Proposal", "Negotiation", "Verbal Commit", "Won", "Lost");
    private static final int DEFAULT_PROBABILITY = 50;

    private final OpportunityRepository opportunityRepository;
    private final CurrentUser           currentUser;
    private final AuditService          auditService;
    private final PermissionService     permissionService;
    private final NotificationService   notificationService;
    private final AiAutomationService   aiAutomationService;
    private final TenantGuard           tenantGuard;

    public OpportunityController(OpportunityRepository opportunityRepository,
                                  CurrentUser currentUser,
                                  AuditService auditService,
                                  PermissionService permissionService,
                                  NotificationService notificationService,
                                  AiAutomationService aiAutomationService,
                                  TenantGuard tenantGuard) {
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
        Long orgId = currentUser.organizationId();
        int max = TenantGuard.clampLimit(limit);

        List<Opportunity> all = opportunityRepository.findByOrganizationId(orgId);

        // Standard role-scope filter
        List<Opportunity> visible = all.stream()
                .filter(o -> matchesSearch(o, search))
                .filter(o -> permissionService.can(actor, Action.VIEW,
                        ResourceType.OPPORTUNITY, o.getOrganizationId(), o.getOwnerId()))
                .collect(Collectors.toList());

        // V5: Inside Sales "own sourced" — additional visibility beyond the standard scope
        // They can see stage/product/competitors of deals from their qualified leads,
        // but not financials. Tracked separately and merged in to avoid double-counting.
        if (actor.getRole() == Role.INSIDE_SALES) {
            Map<Long, Opportunity> visibleById = visible.stream()
                    .collect(Collectors.toMap(Opportunity::getId, o -> o, (a, b) -> a,
                                              LinkedHashMap::new));

            List<Opportunity> sourced = opportunityRepository
                    .findByOrganizationIdAndSourcedByUserId(orgId, actor.getId());
            for (Opportunity o : sourced) {
                // M15: the search term applies to sourced deals too, not just the scoped ones
                if (!matchesSearch(o, search)) continue;
                if (!visibleById.containsKey(o.getId())) {
                    visibleById.put(o.getId(),
                                    permissionService.maskSourcedOpportunityForInsideSales(o));
                }
            }
            List<Opportunity> merged = new ArrayList<>(visibleById.values());
            List<Opportunity> page = merged.stream().limit(max).collect(Collectors.toList());
            return Map.of("count", merged.size(),
                          "data",  permissionService.maskOpportunitiesForResponse(actor, page));
        }

        List<Opportunity> page = visible.stream().limit(max).collect(Collectors.toList());
        return Map.of("count", visible.size(),
                      "data",  permissionService.maskOpportunitiesForResponse(actor, page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Opportunity> get(@PathVariable Long id) {
        Opportunity opp = find(id).orElse(null);
        if (opp == null) throw new NoSuchElementException("opportunity not found");
        User actor = currentUser.get();

        boolean hasStandardAccess = permissionService.can(actor, Action.VIEW,
                ResourceType.OPPORTUNITY, opp.getOrganizationId(), opp.getOwnerId());
        boolean isSourcedAccess = permissionService.canViewSourcedOpportunity(actor, opp);

        if (!hasStandardAccess && !isSourcedAccess) {
            throw new AccessDeniedException(
                    "not permitted: " + actor.getRole() + " cannot VIEW this OPPORTUNITY");
        }

        // Inside Sales via sourced-only path gets a more aggressive field mask
        if (!hasStandardAccess && isSourcedAccess) {
            return ResponseEntity.ok(permissionService.maskSourcedOpportunityForInsideSales(opp));
        }
        return ResponseEntity.ok(permissionService.maskOpportunityForResponse(actor, opp));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Opportunity body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.OPPORTUNITY,
                                  currentUser.organizationId(), null);
        if (body.getName() == null || body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (body.getOwnerId() != null && !body.getOwnerId().equals(actor.getId())) {
            requireAssignableOwner(actor, body.getOwnerId());
        }
        String invalid = validateFields(body.getStage(), body.getProbability(), body.getValue());
        if (invalid != null) return ResponseEntity.badRequest().body(Map.of("error", invalid));

        // Same field rules as update (H12): probability is stage-driven and PO
        // status is Manager+ — a rep can't bypass that by setting them at create
        // time. The form always sends the default probability, so only a value
        // that differs from the default is treated as an attempt to set it.
        if (body.getProbability() != null && body.getProbability() != DEFAULT_PROBABILITY
                && !permissionService.canEditOpportunityProbability(actor)) {
            throw new AccessDeniedException("probability is stage-driven and not directly editable for your role");
        }
        if (body.getPoStatus() != null && !body.getPoStatus().isBlank()
                && !permissionService.canEditOpportunityPoStatus(actor)) {
            throw new AccessDeniedException("payment/PO status can only be set by a Manager or above");
        }
        tenantGuard.requireCompany(body.getCompanyId());
        tenantGuard.requireContact(body.getContactId());

        // H12: the entity is bound straight from JSON — reset every server-owned column.
        // sourceLeadId/sourcedByUserId are only ever set by lead conversion; letting a
        // client set sourcedByUserId would grant an Inside Sales user "sourced"
        // visibility into any deal.
        body.setId(null);
        body.setOrganizationId(currentUser.organizationId());
        body.setSourceLeadId(null);
        body.setSourcedByUserId(null);
        body.setDeletedAt(null);
        body.setDeletedBy(null);
        body.setDeleteReason(null);
        if (body.getOwnerId() == null) body.setOwnerId(currentUser.userId());
        if (body.getStage() == null) body.setStage(STAGES.get(0));
        if (body.getValue() == null) body.setValue(0L);                       // column is NOT NULL
        if (body.getProbability() == null) body.setProbability(DEFAULT_PROBABILITY); // column is NOT NULL
        if (body.getPoStatus() != null && body.getPoStatus().isBlank()) body.setPoStatus(null);
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        opportunityRepository.save(body);
        auditService.log("create", "opportunities", body.getId(), body.getName(), null, body);
        return ResponseEntity.status(201).body(permissionService.maskOpportunityForResponse(actor, body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                     @RequestBody Opportunity body) {
        Opportunity existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("opportunity not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.OPPORTUNITY,
                                  existing.getOrganizationId(), existing.getOwnerId());

        // Field-mask enforcement before applying the edit
        if (body.getProbability() != null && !body.getProbability().equals(existing.getProbability())
                && !permissionService.canEditOpportunityProbability(actor)) {
            throw new AccessDeniedException("probability is stage-driven and not directly editable for your role");
        }
        if (body.getPoStatus() != null && !body.getPoStatus().equals(existing.getPoStatus())
                && !permissionService.canEditOpportunityPoStatus(actor)) {
            throw new AccessDeniedException("payment/PO status can only be set by a Manager or above");
        }
        if (body.getOwnerId() != null && !body.getOwnerId().equals(existing.getOwnerId())) {
            requireAssignableOwner(actor, body.getOwnerId());
        }
        String invalid = validateFields(body.getStage(), body.getProbability(), body.getValue());
        if (invalid != null) return ResponseEntity.badRequest().body(Map.of("error", invalid));
        if (body.getName() != null && body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name cannot be blank"));
        }
        if (body.getCompanyId() != null && !body.getCompanyId().equals(existing.getCompanyId())) {
            tenantGuard.requireCompany(body.getCompanyId());
        }
        if (body.getContactId() != null && !body.getContactId().equals(existing.getContactId())) {
            tenantGuard.requireContact(body.getContactId());
        }

        Opportunity before = copyOf(existing);
        if (body.getName()              != null) existing.setName(body.getName());
        if (body.getCompanyId()         != null) existing.setCompanyId(body.getCompanyId());
        if (body.getContactId()         != null) existing.setContactId(body.getContactId());
        if (body.getOwnerId()           != null) existing.setOwnerId(body.getOwnerId());
        if (body.getStage()             != null) existing.setStage(body.getStage());
        if (body.getValue()             != null) existing.setValue(body.getValue());
        if (body.getProbability()       != null) existing.setProbability(body.getProbability());
        if (body.getCloseDate()         != null) existing.setCloseDate(body.getCloseDate());
        if (body.getNotes()             != null) existing.setNotes(body.getNotes());
        if (body.getServiceProduct()    != null) existing.setServiceProduct(body.getServiceProduct());
        if (body.getCompetitors()       != null) existing.setCompetitors(body.getCompetitors());
        if (body.getCurrentVendor()     != null) existing.setCurrentVendor(body.getCurrentVendor());
        if (body.getProposalStatus()    != null) existing.setProposalStatus(body.getProposalStatus());
        if (body.getNegotiationStatus() != null) existing.setNegotiationStatus(body.getNegotiationStatus());
        if (body.getPoStatus()          != null) existing.setPoStatus(body.getPoStatus());
        existing.setUpdatedAt(Instant.now());

        opportunityRepository.save(existing);
        auditService.log("update", "opportunities", id, existing.getName(), before, existing);
        String newStage = existing.getStage();
        boolean stageChanged = newStage != null && !newStage.equals(before.getStage());
        if (stageChanged && existing.getOwnerId() != null && !existing.getOwnerId().equals(actor.getId())) {
            notificationService.notifyOppStageChanged(actor.getOrganizationId(),
                    existing.getOwnerId(), existing.getName(), newStage, existing.getId());
        }
        if (stageChanged) {
            Map<String, String> ctx = new HashMap<>();
            ctx.put("opportunity.id", String.valueOf(existing.getId()));
            ctx.put("opportunity.name", existing.getName());
            ctx.put("opportunity.stage", newStage);
            ctx.put("opportunity.value", String.valueOf(existing.getValue()));
            ctx.put("task", "Opportunity \"" + existing.getName() + "\" moved to stage " + newStage);
            aiAutomationService.fireEvent(actor.getOrganizationId(), "on_stage_change", ctx);
        }
        return ResponseEntity.ok(permissionService.maskOpportunityForResponse(actor, existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id,
                                     @RequestParam(required = false) String reason) {
        Opportunity existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("opportunity not found");
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
                .orElse(null);
        if (existing == null) throw new NoSuchElementException("opportunity not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.OPPORTUNITY,
                                  existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(null);
        existing.setDeletedBy(null);
        existing.setDeleteReason(null);
        opportunityRepository.save(existing);
        auditService.log("update", "opportunities", id, existing.getName(), null,
                         Map.of("restored", true));
        return ResponseEntity.ok(permissionService.maskOpportunityForResponse(actor, existing));
    }

    @GetMapping("/trash")
    public List<Opportunity> trash() {
        User actor = currentUser.get();
        return opportunityRepository.findTrashedByOrganizationId(currentUser.organizationId())
                .stream()
                .filter(o -> permissionService.can(actor, Action.DELETE, ResourceType.OPPORTUNITY,
                                                   o.getOrganizationId(), o.getOwnerId()))
                .map(o -> permissionService.maskOpportunityForResponse(actor, o))
                .collect(Collectors.toList());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static boolean matchesSearch(Opportunity o, String search) {
        if (search == null || search.isBlank()) return true;
        return o.getName() != null && o.getName().toLowerCase().contains(search.toLowerCase());
    }

    /**
     * H11: the new owner must be a user in this org, the actor must be allowed to
     * assign at all (Manager+), and a Manager may only assign within their team —
     * otherwise a Manager could push deals to (or pull visibility from) other teams.
     */
    private void requireAssignableOwner(User actor, Long ownerId) {
        tenantGuard.requireUser(ownerId);
        if (!permissionService.canEditLeadOwner(actor) || !permissionService.canAssignTo(actor, ownerId)) {
            throw new AccessDeniedException("you are not permitted to assign this opportunity to that user");
        }
    }

    /** M14: stage must be a known pipeline stage, probability 0..100, value non-negative. */
    static String validateFields(String stage, Integer probability, Long value) {
        if (stage != null && !STAGES.contains(stage)) return "stage must be one of " + STAGES;
        if (probability != null && (probability < 0 || probability > 100)) return "probability must be between 0 and 100";
        if (value != null && value < 0) return "value must not be negative";
        return null;
    }

    private Optional<Opportunity> find(Long id) {
        return opportunityRepository.findByIdAndOrganizationId(id, currentUser.organizationId());
    }

    private Opportunity copyOf(Opportunity o) {
        Opportunity c = new Opportunity();
        c.setId(o.getId()); c.setOrganizationId(o.getOrganizationId()); c.setName(o.getName());
        c.setCompanyId(o.getCompanyId()); c.setContactId(o.getContactId()); c.setOwnerId(o.getOwnerId());
        c.setStage(o.getStage()); c.setValue(o.getValue()); c.setProbability(o.getProbability());
        c.setCloseDate(o.getCloseDate()); c.setNotes(o.getNotes()); c.setServiceProduct(o.getServiceProduct());
        c.setCompetitors(o.getCompetitors()); c.setCurrentVendor(o.getCurrentVendor());
        c.setProposalStatus(o.getProposalStatus()); c.setNegotiationStatus(o.getNegotiationStatus());
        c.setPoStatus(o.getPoStatus()); c.setSourceLeadId(o.getSourceLeadId());
        c.setSourcedByUserId(o.getSourcedByUserId());
        c.setCreatedAt(o.getCreatedAt()); c.setUpdatedAt(o.getUpdatedAt());
        return c;
    }
}
