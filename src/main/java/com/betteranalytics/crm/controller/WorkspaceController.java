package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.Workspace;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.WorkspaceRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Org workspaces. Everyone can view; create/update/delete are gated by the
 * WORKSPACE rows of the feature-permission matrix. The org's default
 * workspace can't be deleted, and "set default" is serialised per org so
 * there is never more than one default.
 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceRepository workspaceRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    public WorkspaceController(WorkspaceRepository workspaceRepository,
                                CurrentUser currentUser, AuditService auditService,
                                PermissionService permissionService, ObjectMapper objectMapper) {
        this.workspaceRepository = workspaceRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Workspace> list() {
        return workspaceRepository.findByOrganizationId(currentUser.organizationId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Workspace> get(@PathVariable Long id) {
        return workspaceRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.WORKSPACE, actor.getOrganizationId(), null);
        Workspace body = bind(raw);
        if (body.getName() == null || body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        Workspace w = new Workspace();
        w.setOrganizationId(actor.getOrganizationId());
        w.setName(body.getName());
        w.setDescription(body.getDescription());
        if (body.getIcon() != null) w.setIcon(body.getIcon());
        if (body.getColor() != null) w.setColor(body.getColor());
        w.setCreatedBy(actor.getId());
        w.setCreatedAt(Instant.now());
        w.setUpdatedAt(Instant.now());
        boolean makeDefault = body.isDefault();
        if (makeDefault) workspaceRepository.lockAllByOrganizationId(actor.getOrganizationId());
        w.setDefault(makeDefault);
        Workspace saved = workspaceRepository.save(w);
        // Only one default workspace per org — cleared in the same transaction.
        if (makeDefault) workspaceRepository.clearOtherDefaults(actor.getOrganizationId(), saved.getId());
        auditService.log("create", "workspaces", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        Workspace existing = workspaceRepository.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.WORKSPACE, existing.getOrganizationId(), existing.getCreatedBy());
        Workspace body = bind(raw);
        if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        if (body.getIcon() != null) existing.setIcon(body.getIcon());
        if (body.getColor() != null) existing.setColor(body.getColor());
        boolean settingDefault = raw.containsKey("default") && body.isDefault() && !existing.isDefault();
        if (raw.containsKey("default")) {
            if (settingDefault) workspaceRepository.lockAllByOrganizationId(actor.getOrganizationId());
            existing.setDefault(body.isDefault());
        }
        existing.setUpdatedAt(Instant.now());
        Workspace saved = workspaceRepository.save(existing);
        if (settingDefault) workspaceRepository.clearOtherDefaults(actor.getOrganizationId(), saved.getId());
        auditService.log("update", "workspaces", id, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        Workspace existing = workspaceRepository.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.WORKSPACE, existing.getOrganizationId(), existing.getCreatedBy());
        if (existing.isDefault()) {
            return ResponseEntity.status(409).body(Map.of("error", "the default workspace cannot be deleted — make another workspace the default first"));
        }
        workspaceRepository.delete(existing);
        auditService.log("delete", "workspaces", id, existing.getName(), existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private Workspace bind(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, Workspace.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
