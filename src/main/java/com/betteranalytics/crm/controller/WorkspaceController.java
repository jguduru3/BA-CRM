package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.Workspace;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.WorkspaceRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Org workspaces — the model a future workspace switcher reads. (Per-workspace
 * data scoping is intentionally NOT implemented yet; it awaits a product call.)
 *
 * GET    /api/workspaces                 — every workspace of the org, default first
 * GET    /api/workspaces/default         — the org's default workspace (404 when the org has none yet)
 * GET    /api/workspaces/{id}
 * POST   /api/workspaces                 — create; the org's first workspace always becomes the default
 * PUT    /api/workspaces/{id}            — partial update; {"default": true} promotes it, and
 *                                          {"default": false, "newDefaultId": X} hands the default to X
 * DELETE /api/workspaces/{id}            — the default can only be deleted with ?newDefaultId=X (409 otherwise)
 *
 * Exactly one default per org is enforced by WorkspaceService AND by a unique
 * index in the database (V40). Access: PermissionService on WORKSPACE.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    public WorkspaceController(WorkspaceRepository workspaceRepository, WorkspaceService workspaceService,
                                CurrentUser currentUser, AuditService auditService,
                                PermissionService permissionService, ObjectMapper objectMapper) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceService = workspaceService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Workspace> list() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.WORKSPACE, actor.getOrganizationId(), actor.getId());
        return workspaceRepository.findForSwitcher(actor.getOrganizationId());
    }

    @GetMapping("/default")
    public ResponseEntity<Workspace> getDefault() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.WORKSPACE, actor.getOrganizationId(), actor.getId());
        return workspaceRepository.findByOrganizationIdAndIsDefaultTrue(actor.getOrganizationId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Workspace> get(@PathVariable Long id) {
        User actor = currentUser.get();
        Workspace w = workspaceRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (w == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.WORKSPACE, w.getOrganizationId(), w.getCreatedBy());
        return ResponseEntity.ok(w);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.WORKSPACE, actor.getOrganizationId(), null);
        Workspace body = bind(raw);
        if (body.getName() == null || body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        Workspace saved = workspaceService.create(actor, body, body.isDefault());
        auditService.log("create", "workspaces", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        Workspace existing = workspaceRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.WORKSPACE, existing.getOrganizationId(), existing.getCreatedBy());
        Workspace body = bind(raw);
        if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName().trim());
        if (raw.containsKey("description")) existing.setDescription(body.getDescription());
        // icon/color have field defaults — only explicitly sent values count.
        if (raw.containsKey("icon") && body.getIcon() != null) existing.setIcon(body.getIcon());
        if (raw.containsKey("color") && body.getColor() != null) existing.setColor(body.getColor());
        existing.setUpdatedAt(Instant.now());
        workspaceRepository.saveAndFlush(existing);
        Workspace saved = existing;
        if (raw.containsKey("default")) {
            Long replacement = raw.get("newDefaultId") instanceof Number n ? n.longValue() : null;
            saved = workspaceService.changeDefault(actor.getOrganizationId(), id, body.isDefault(), replacement);
        }
        auditService.log("update", "workspaces", id, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam(required = false) Long newDefaultId) {
        User actor = currentUser.get();
        Workspace existing = workspaceRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.WORKSPACE, existing.getOrganizationId(), existing.getCreatedBy());
        if (newDefaultId != null) {
            // promoting the replacement is an edit of the workspace model
            permissionService.require(actor, Action.EDIT, ResourceType.WORKSPACE, existing.getOrganizationId(), existing.getCreatedBy());
        }
        Workspace deleted = workspaceService.delete(actor.getOrganizationId(), id, newDefaultId);
        auditService.log("delete", "workspaces", id, deleted.getName(), deleted, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private Workspace bind(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            Map<String, Object> copy = new java.util.HashMap<>(raw);
            copy.remove("newDefaultId");
            return objectMapper.convertValue(copy, Workspace.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
