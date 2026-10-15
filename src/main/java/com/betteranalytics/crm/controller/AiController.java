package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.AiJob;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.AiJobRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AiService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * AI automation endpoints — all require JWT auth.
 *
 * POST /api/ai/leads/{id}/score         — AI lead score (0-100) with reasoning
 * POST /api/ai/leads/{id}/draft-message — draft a WhatsApp/email message
 * POST /api/ai/leads/{id}/summarize     — summarize activities in 2-3 sentences
 * POST /api/ai/leads/{id}/next-action   — suggest next best action
 * GET  /api/ai/jobs                     — list AI jobs (filterable by entity; ?limit=1..200)
 * GET  /api/ai/jobs/{id}                — get single job + result
 *
 * Every lead endpoint requires VIEW on that lead (owner/team scope), since the
 * lead's details are sent to the AI provider and echoed back in the result.
 * Org AI must be enabled and an API key set via PUT /api/settings/ai before
 * any of the POST endpoints will succeed.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiService         aiService;
    private final AiJobRepository   jobRepo;
    private final LeadRepository    leadRepo;
    private final PermissionService permissionService;
    private final CurrentUser       currentUser;

    public AiController(AiService aiService, AiJobRepository jobRepo, LeadRepository leadRepo,
                        PermissionService permissionService, CurrentUser currentUser) {
        this.aiService         = aiService;
        this.jobRepo           = jobRepo;
        this.leadRepo          = leadRepo;
        this.permissionService = permissionService;
        this.currentUser       = currentUser;
    }

    @PostMapping("/leads/{leadId}/score")
    public ResponseEntity<AiJob> scoreLead(@PathVariable Long leadId) {
        User actor = requireLeadAccess(leadId);
        return ResponseEntity.ok(
                aiService.scoreLead(actor.getOrganizationId(), leadId, actor.getId()));
    }

    @PostMapping("/leads/{leadId}/draft-message")
    public ResponseEntity<AiJob> draftMessage(
            @PathVariable Long leadId,
            @RequestBody(required = false) Map<String, Object> req) {
        User actor = requireLeadAccess(leadId);
        String channel = req != null && req.get("channel") instanceof String s ? s : null;
        String context = req != null && req.get("context") instanceof String s ? s : null;
        if (channel != null && !channel.matches("^[A-Za-z_ -]{1,30}$"))
            throw new IllegalArgumentException("channel is not valid");
        if (context != null && context.length() > 4000)
            throw new IllegalArgumentException("context exceeds 4000 characters");
        return ResponseEntity.ok(
                aiService.draftMessage(actor.getOrganizationId(), leadId, channel, context, actor.getId()));
    }

    @PostMapping("/leads/{leadId}/summarize")
    public ResponseEntity<AiJob> summarize(@PathVariable Long leadId) {
        User actor = requireLeadAccess(leadId);
        return ResponseEntity.ok(
                aiService.summarize(actor.getOrganizationId(), leadId, actor.getId()));
    }

    @PostMapping("/leads/{leadId}/next-action")
    public ResponseEntity<AiJob> nextAction(@PathVariable Long leadId) {
        User actor = requireLeadAccess(leadId);
        return ResponseEntity.ok(
                aiService.nextAction(actor.getOrganizationId(), leadId, actor.getId()));
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<AiJob>> listJobs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(defaultValue = "100") int limit) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.AI_JOB, actor.getOrganizationId(), null);
        Long orgId = actor.getOrganizationId();
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(200, limit)));
        if (entityType != null && entityId != null) {
            if ("Lead".equals(entityType)) requireLeadView(actor, entityId);
            else if (!isAdmin(actor)) return ResponseEntity.ok(List.of());
            return ResponseEntity.ok(
                    jobRepo.findByOrganizationIdAndEntityTypeAndEntityIdOrderByCreatedAtDesc(
                            orgId, entityType, entityId, page));
        }
        // Unfiltered: admins see the org's jobs; everyone else only the jobs they ran.
        if (isAdmin(actor)) return ResponseEntity.ok(jobRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId, page));
        return ResponseEntity.ok(jobRepo.findByOrganizationIdAndCreatedByOrderByCreatedAtDesc(orgId, actor.getId(), page));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<AiJob> getJob(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.AI_JOB, actor.getOrganizationId(), null);
        AiJob job = jobRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        if ("Lead".equals(job.getEntityType()) && job.getEntityId() != null) {
            requireLeadView(actor, job.getEntityId());
        } else if (!isAdmin(actor) && !actor.getId().equals(job.getCreatedBy())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User requireLeadAccess(Long leadId) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.AI_JOB, actor.getOrganizationId(), null);
        requireLeadView(actor, leadId);
        return actor;
    }

    private void requireLeadView(User actor, Long leadId) {
        Lead lead = leadRepo.findByIdAndOrganizationId(leadId, actor.getOrganizationId())
                .orElseThrow(() -> new NoSuchElementException("Lead not found"));
        permissionService.require(actor, Action.VIEW, ResourceType.LEAD, lead.getOrganizationId(), lead.getOwnerId());
    }

    private static boolean isAdmin(User actor) {
        return actor.getRole() == Role.SUPER_ADMIN || actor.getRole() == Role.VP_SALES;
    }
}
