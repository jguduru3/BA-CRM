package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Reminder;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ReminderRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.Paging;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET    /api/reminders         — the caller's reminders (?fired=true|false&page=&size=)
 * POST   /api/reminders         — create
 * DELETE /api/reminders/{id}    — delete (own reminders only)
 *
 * Reminders fire automatically via ReminderSchedulerService every minute.
 * Reminders are personal: PermissionService grants REMINDER at OWN scope only,
 * so someone else's reminder is indistinguishable from a missing one (404).
 */
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderRepository repo;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final TenantGuard tenantGuard;

    public ReminderController(ReminderRepository repo, CurrentUser currentUser,
                              PermissionService permissionService, AuditService auditService,
                              TenantGuard tenantGuard) {
        this.repo = repo;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public List<Reminder> list(@RequestParam(required = false) Boolean fired,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.REMINDER, actor.getOrganizationId(), actor.getId());
        return repo.findForUser(actor.getOrganizationId(), actor.getId(), fired, Paging.of(page, size, 500, 500, null));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Reminder body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.REMINDER, actor.getOrganizationId(), null);
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        if (body.getRemindAt() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "remindAt is required"));
        }
        String relatedType = tenantGuard.requireRelated(body.getRelatedType(), body.getRelatedId());
        // Copy only client-controlled fields; owner/org/fired state are server-controlled.
        Reminder r = new Reminder();
        r.setOrganizationId(actor.getOrganizationId());
        r.setUserId(actor.getId());
        r.setTitle(body.getTitle().trim());
        r.setBody(body.getBody());
        r.setRemindAt(body.getRemindAt());
        r.setRelatedType(relatedType);
        r.setRelatedId(relatedType != null ? body.getRelatedId() : null);
        r.setFired(false);
        r.setFiredAt(null);
        r.setCreatedAt(Instant.now());
        Reminder saved = repo.save(r);
        auditService.log("create", "reminders", saved.getId(), saved.getTitle(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        Reminder existing = repo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null
                || !permissionService.can(actor, Action.VIEW, ResourceType.REMINDER, existing.getOrganizationId(), existing.getUserId())) {
            return ResponseEntity.notFound().build();
        }
        permissionService.require(actor, Action.DELETE, ResourceType.REMINDER, existing.getOrganizationId(), existing.getUserId());
        repo.delete(existing);
        auditService.log("delete", "reminders", id, existing.getTitle(), existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
