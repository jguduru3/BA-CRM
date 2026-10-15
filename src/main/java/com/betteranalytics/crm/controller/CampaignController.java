package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AiAutomationService;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.WhatsAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Campaign lifecycle management.
 *
 * GET    /api/campaigns                      — list (filterable by ?status=, ?limit=1..200)
 * POST   /api/campaigns                      — create (draft)
 * GET    /api/campaigns/{id}                 — get campaign + contacts (first page)
 * PUT    /api/campaigns/{id}                 — update (draft only)
 * DELETE /api/campaigns/{id}                 — delete (draft only)
 * POST   /api/campaigns/{id}/launch          — approved → running/scheduled (draft → … SUPER_ADMIN only)
 * POST   /api/campaigns/{id}/pause           — running → paused
 * POST   /api/campaigns/{id}/resume          — paused → running
 * POST   /api/campaigns/{id}/cancel          — any → cancelled
 * GET    /api/campaigns/{id}/contacts        — list recipients (?page=, ?size=1..200)
 * POST   /api/campaigns/{id}/contacts        — add recipients (bulk, max 5000 per request)
 * DELETE /api/campaigns/{id}/contacts/{ccId} — remove recipient (draft only)
 *
 * WhatsApp dispatch runs in the background (WhatsAppService); due scheduled
 * campaigns are started by WhatsAppService.runDueCampaigns().
 */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    // Approval flow added in V10:
    //   draft → pending_approval → approved → scheduled/running
    // Legacy direct launch (draft → running/scheduled) still allowed for SUPER_ADMIN.
    private static final Set<String> VALID_TRANSITIONS = Set.of(
            "draft→running", "draft→scheduled", "draft→pending_approval",
            "pending_approval→approved", "pending_approval→draft",
            "approved→scheduled", "approved→running",
            "scheduled→running",
            "running→paused", "paused→running",
            "running→completed", "running→cancelled", "scheduled→cancelled",
            "draft→cancelled", "paused→cancelled", "pending_approval→cancelled",
            "approved→cancelled"
    );
    private static final Set<String> TYPES = Set.of("whatsapp", "email", "sms");
    private static final Set<String> RELATED_TYPES = Set.of("Lead", "Contact");
    private static final int MAX_CONTACTS_PER_REQUEST = 5000;

    private final CampaignRepository        campaignRepo;
    private final CampaignContactRepository contactRepo;
    private final WhatsAppAccountRepository waAccountRepo;
    private final LeadRepository            leadRepo;
    private final ContactRepository         crmContactRepo;
    private final WhatsAppService           whatsAppService;
    private final AiAutomationService       aiAutomationService;
    private final PermissionService         permissionService;
    private final CurrentUser               currentUser;
    private final NotificationService       notificationService;
    private final AuditService              auditService;
    private final ObjectMapper              objectMapper;

    public CampaignController(CampaignRepository campaignRepo,
                               CampaignContactRepository contactRepo,
                               WhatsAppAccountRepository waAccountRepo,
                               LeadRepository leadRepo,
                               ContactRepository crmContactRepo,
                               WhatsAppService whatsAppService,
                               AiAutomationService aiAutomationService,
                               PermissionService permissionService,
                               CurrentUser currentUser,
                               NotificationService notificationService,
                               AuditService auditService,
                               ObjectMapper objectMapper) {
        this.campaignRepo = campaignRepo;
        this.contactRepo = contactRepo;
        this.waAccountRepo = waAccountRepo;
        this.leadRepo = leadRepo;
        this.crmContactRepo = crmContactRepo;
        this.whatsAppService = whatsAppService;
        this.aiAutomationService = aiAutomationService;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Campaign>> list(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "200") int limit) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        PageRequest page = PageRequest.of(0, clamp(limit));
        if (status != null)
            return ResponseEntity.ok(campaignRepo.findByOrganizationIdAndStatusOrderByCreatedAtDesc(actor.getOrganizationId(), status, page));
        return ResponseEntity.ok(campaignRepo.findByOrganizationIdOrderByCreatedAtDesc(actor.getOrganizationId(), page));
    }

    @PostMapping
    public ResponseEntity<Campaign> create(@RequestBody Campaign body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);

        // Build a fresh entity from allow-listed fields: the client can never set
        // id / organizationId / status / approval fields / counters / timestamps.
        Campaign c = new Campaign();
        c.setOrganizationId(actor.getOrganizationId());
        c.setName(requireName(body.getName()));
        String type = body.getType() != null ? body.getType() : "whatsapp";
        if (!TYPES.contains(type)) throw new IllegalArgumentException("type must be whatsapp, email or sms");
        c.setType(type);
        applyEditableFields(c, body, actor.getOrganizationId());
        c.setCreatedBy(actor.getId());
        c.setOwnerId(actor.getId());
        c.setStatus("draft");
        c.setTotalContacts(0);
        c.setSentCount(0);
        c.setDeliveredCount(0);
        c.setReadCount(0);
        c.setFailedCount(0);
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return ResponseEntity.ok(campaignRepo.save(c));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("campaign", c, "contacts",
                contactRepo.findByCampaignIdAndOrganizationIdOrderByIdAsc(id, actor.getOrganizationId(), PageRequest.of(0, 200))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Campaign> update(@PathVariable Long id, @RequestBody Campaign body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        requireStatus(c, "draft");
        if (body.getName() != null) c.setName(requireName(body.getName()));
        applyEditableFields(c, body, actor.getOrganizationId());
        c.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(campaignRepo.save(c));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        requireStatus(c, "draft");
        campaignRepo.delete(c);
        return ResponseEntity.noContent().build();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/launch")
    @Transactional
    public ResponseEntity<Campaign> launch(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();

        // Launch requires approval; only SUPER_ADMIN may bypass it from draft.
        if ("draft".equals(c.getStatus())) {
            if (actor.getRole() != Role.SUPER_ADMIN)
                throw new AccessDeniedException("Campaign must be approved before launch (only SUPER_ADMIN may launch a draft)");
        } else if (!"approved".equals(c.getStatus())) {
            throw new IllegalStateException("Campaign must be approved before launch, got '" + c.getStatus() + "'");
        }
        if ("whatsapp".equals(c.getType())) requireWhatsAppReady(c);

        String targetStatus = c.getScheduledAt() != null && c.getScheduledAt().isAfter(Instant.now())
                ? "scheduled" : "running";
        transition(c, targetStatus);
        Campaign saved = campaignRepo.save(c);
        auditService.log("launch", "campaigns", c.getId(), c.getName(), null,
                Map.of("launchedBy", actor.getId(), "status", targetStatus));

        if ("running".equals(targetStatus) && "whatsapp".equals(c.getType())) {
            whatsAppService.dispatchCampaignAsync(c.getId());   // after commit, on the bounded executor
        }
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Campaign> pause(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        transition(c, "paused");
        return ResponseEntity.ok(campaignRepo.save(c));
    }

    @PostMapping("/{id}/resume")
    @Transactional
    public ResponseEntity<Campaign> resume(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        transition(c, "running");
        Campaign saved = campaignRepo.save(c);
        if ("whatsapp".equals(c.getType())) whatsAppService.dispatchCampaignAsync(c.getId());
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Campaign> cancel(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        transition(c, "cancelled");
        return ResponseEntity.ok(campaignRepo.save(c));
    }

    // ── Approval workflow ─────────────────────────────────────────────────────

    /** Submit draft campaign for approval — draft → pending_approval. */
    @PostMapping("/{id}/submit")
    public ResponseEntity<Campaign> submit(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        transition(c, "pending_approval");
        c.setSubmittedAt(Instant.now());
        c.setApprovedBy(null);
        c.setApprovedAt(null);
        campaignRepo.save(c);
        auditService.log("submit", "campaigns", c.getId(), c.getName(), null, Map.of("submittedBy", actor.getId()));
        // Notify VP_SALES / SUPER_ADMIN that a campaign awaits approval
        notificationService.notifyAdmins(actor.getOrganizationId(), "approval_requested",
                "Campaign pending approval: " + c.getName(),
                "Submitted by " + actor.getName(), "Campaign", c.getId());
        return ResponseEntity.ok(c);
    }

    /** Approve a pending_approval campaign — pending_approval → approved. SUPER_ADMIN / VP_SALES only. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Campaign> approve(@PathVariable Long id) {
        User actor = currentUser.get();
        requireApprover(actor);
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        // Four-eyes: VP_SALES cannot approve their own campaign; SUPER_ADMIN may.
        if (actor.getId().equals(c.getCreatedBy()) && actor.getRole() != Role.SUPER_ADMIN)
            throw new AccessDeniedException("You cannot approve a campaign you created");
        transition(c, "approved");
        c.setApprovedBy(actor.getId());
        c.setApprovedAt(Instant.now());
        c.setRejectionReason(null);
        campaignRepo.save(c);
        auditService.log("approve", "campaigns", c.getId(), c.getName(), null, Map.of("approvedBy", actor.getId()));
        // Notify the campaign creator
        if (c.getCreatedBy() != null && !c.getCreatedBy().equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), c.getCreatedBy(),
                    "campaign_approved", "Campaign approved: " + c.getName(),
                    "Approved by " + actor.getName(), "Campaign", c.getId());
        }
        Map<String, String> ctx = new HashMap<>();
        ctx.put("campaign.id", String.valueOf(c.getId()));
        ctx.put("campaign.name", c.getName());
        ctx.put("campaign.type", c.getType());
        aiAutomationService.fireEvent(actor.getOrganizationId(), "on_campaign_approved", ctx);
        return ResponseEntity.ok(c);
    }

    /** Reject and send back to draft — pending_approval → draft. SUPER_ADMIN / VP_SALES only. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Campaign> reject(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        User actor = currentUser.get();
        requireApprover(actor);
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        if (actor.getId().equals(c.getCreatedBy()) && actor.getRole() != Role.SUPER_ADMIN)
            throw new AccessDeniedException("You cannot review a campaign you created");
        String reason = body != null && body.get("reason") instanceof String s && !s.isBlank()
                ? (s.length() > 2000 ? s.substring(0, 2000) : s) : null;
        transition(c, "draft");
        c.setRejectionReason(reason);
        c.setApprovedBy(null);
        c.setApprovedAt(null);
        campaignRepo.save(c);
        auditService.log("reject", "campaigns", c.getId(), c.getName(), null, Map.of("rejectedBy", actor.getId()));
        if (c.getCreatedBy() != null && !c.getCreatedBy().equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), c.getCreatedBy(),
                    "campaign_rejected", "Campaign rejected: " + c.getName(),
                    reason != null ? reason : "No reason given", "Campaign", c.getId());
        }
        return ResponseEntity.ok(c);
    }

    // ── Contacts ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}/contacts")
    public ResponseEntity<List<CampaignContact>> listContacts(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "200") int size) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        if (find(id, actor) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(contactRepo.findByCampaignIdAndOrganizationIdOrderByIdAsc(
                id, actor.getOrganizationId(), PageRequest.of(Math.max(0, page), clamp(size))));
    }

    @PostMapping("/{id}/contacts")
    @Transactional
    public ResponseEntity<Map<String, Object>> addContacts(@PathVariable Long id,
                                                            @RequestBody List<Map<String, Object>> entries) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        requireStatus(c, "draft");
        if (entries == null) throw new IllegalArgumentException("body must be an array of recipients");
        if (entries.size() > MAX_CONTACTS_PER_REQUEST)
            throw new IllegalArgumentException("At most " + MAX_CONTACTS_PER_REQUEST + " recipients per request");

        Long orgId = actor.getOrganizationId();
        Set<String> seen = new HashSet<>(contactRepo.findPhoneNumbersByCampaignId(id));
        List<CampaignContact> toSave = new ArrayList<>();
        int invalid = 0, duplicates = 0;
        Instant now = Instant.now();
        for (Map<String, Object> e : entries) {
            if (e == null) { invalid++; continue; }
            Object rawPhone = e.get("phoneNumber") != null ? e.get("phoneNumber") : e.get("phone");
            if (!(rawPhone instanceof String p) || !WhatsAppService.isValidPhone(p)) { invalid++; continue; }
            String phoneNumber = WhatsAppService.normalizePhone(p);
            if (!seen.add(phoneNumber)) { duplicates++; continue; }

            CampaignContact cc = new CampaignContact();
            cc.setCampaignId(id);
            cc.setOrganizationId(orgId);
            cc.setPhoneNumber(phoneNumber);
            cc.setStatus("pending");
            if (e.get("name") instanceof String n && !n.isBlank())
                cc.setName(n.length() > 200 ? n.substring(0, 200) : n);
            if (e.get("relatedType") != null || e.get("relatedId") != null) {
                if (!(e.get("relatedType") instanceof String rt) || !RELATED_TYPES.contains(rt))
                    throw new IllegalArgumentException("relatedType must be Lead or Contact");
                Long rid = e.get("relatedId") instanceof Number num && !(num instanceof Double) && !(num instanceof Float)
                        ? num.longValue() : null;
                if (rid == null) throw new IllegalArgumentException("relatedId must be an integer id");
                boolean exists = "Lead".equals(rt)
                        ? leadRepo.findByIdAndOrganizationId(rid, orgId).isPresent()
                        : crmContactRepo.findByIdAndOrganizationId(rid, orgId).isPresent();
                if (!exists) throw new IllegalArgumentException(rt + " " + rid + " not found");
                cc.setRelatedType(rt);
                cc.setRelatedId(rid);
            }
            cc.setCreatedAt(now);
            toSave.add(cc);
        }
        contactRepo.saveAll(toSave);
        contactRepo.flush();
        c.setTotalContacts((int) contactRepo.countByCampaignId(id));
        c.setUpdatedAt(now);
        campaignRepo.save(c);
        return ResponseEntity.ok(Map.of("added", toSave.size(), "invalid", invalid,
                "duplicates", duplicates, "total", c.getTotalContacts()));
    }

    @DeleteMapping("/{id}/contacts/{ccId}")
    @Transactional
    public ResponseEntity<Void> removeContact(@PathVariable Long id, @PathVariable Long ccId) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CAMPAIGN, actor.getOrganizationId(), null);
        Campaign c = find(id, actor);
        if (c == null) return ResponseEntity.notFound().build();
        requireStatus(c, "draft");
        CampaignContact cc = contactRepo.findByIdAndOrganizationId(ccId, actor.getOrganizationId()).orElse(null);
        if (cc == null || !cc.getCampaignId().equals(id)) return ResponseEntity.notFound().build();
        contactRepo.delete(cc);
        contactRepo.flush();
        c.setTotalContacts((int) contactRepo.countByCampaignId(id));
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Campaign find(Long id, User actor) {
        return campaignRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
    }

    /** Copies the client-editable fields (validated) onto a campaign. Nulls leave fields unchanged. */
    private void applyEditableFields(Campaign c, Campaign body, Long orgId) {
        if (body.getTemplateName() != null)
            c.setTemplateName(body.getTemplateName().isBlank() ? null : WhatsAppService.validateTemplateName(body.getTemplateName()));
        if (body.getTemplateLanguage() != null)
            c.setTemplateLanguage(WhatsAppService.validateLanguage(body.getTemplateLanguage()));
        if (body.getTemplateParams() != null) {
            whatsAppService.parseTemplateComponents(body.getTemplateParams());   // 400 when not a JSON array
            c.setTemplateParams(body.getTemplateParams().isBlank() ? null : body.getTemplateParams());
        }
        if (body.getMessageBody() != null) c.setMessageBody(body.getMessageBody());
        if (body.getScheduledAt() != null) c.setScheduledAt(body.getScheduledAt());
        if (body.getAudienceCriteriaJson() != null) {
            if (!body.getAudienceCriteriaJson().isBlank()) {
                try { objectMapper.readTree(body.getAudienceCriteriaJson()); }
                catch (Exception e) { throw new IllegalArgumentException("audienceCriteriaJson must be valid JSON"); }
            }
            c.setAudienceCriteriaJson(body.getAudienceCriteriaJson().isBlank() ? null : body.getAudienceCriteriaJson());
        }
        if (body.getWaAccountId() != null) {
            if (waAccountRepo.findByIdAndOrganizationId(body.getWaAccountId(), orgId).isEmpty())
                throw new IllegalArgumentException("waAccountId does not refer to a WhatsApp account in your organization");
            c.setWaAccountId(body.getWaAccountId());
        }
    }

    private void requireWhatsAppReady(Campaign c) {
        if (c.getTemplateName() == null || c.getTemplateName().isBlank())
            throw new IllegalStateException("A WhatsApp campaign needs a templateName before launch");
        boolean hasAccount = c.getWaAccountId() != null
                ? waAccountRepo.findByIdAndOrganizationId(c.getWaAccountId(), c.getOrganizationId())
                        .map(WhatsAppAccount::isActive).orElse(false)
                : waAccountRepo.findFirstByOrganizationIdAndActiveTrue(c.getOrganizationId()).isPresent();
        if (!hasAccount) throw new IllegalStateException("No active WhatsApp account for this campaign");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        String n = name.trim();
        if (n.length() > 200) throw new IllegalArgumentException("name exceeds 200 characters");
        return n;
    }

    private static void requireApprover(User actor) {
        if (actor.getRole() != Role.SUPER_ADMIN && actor.getRole() != Role.VP_SALES)
            throw new AccessDeniedException("Only SUPER_ADMIN or VP_SALES can review campaigns");
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(200, n));
    }

    private void transition(Campaign c, String to) {
        String key = c.getStatus() + "→" + to;
        if (!VALID_TRANSITIONS.contains(key))
            throw new IllegalStateException("Invalid campaign transition: " + key);
        c.setStatus(to);
        c.setUpdatedAt(Instant.now());
        if ("running".equals(to) && c.getStartedAt() == null) c.setStartedAt(Instant.now());
        if ("completed".equals(to) || "cancelled".equals(to)) c.setCompletedAt(Instant.now());
    }

    private void requireStatus(Campaign c, String required) {
        if (!required.equals(c.getStatus()))
            throw new IllegalStateException("Campaign must be in '" + required + "' status, got '" + c.getStatus() + "'");
    }
}
