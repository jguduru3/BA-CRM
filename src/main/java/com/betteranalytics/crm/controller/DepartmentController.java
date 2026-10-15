package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.dto.DepartmentRequest;
import com.betteranalytics.crm.entity.Department;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.DepartmentRepository;
import com.betteranalytics.crm.repository.TeamRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/departments")
public class DepartmentController {

    private final DepartmentRepository departmentRepository;
    private final TeamRepository teamRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final TenantGuard tenantGuard;

    public DepartmentController(DepartmentRepository departmentRepository,
                                 TeamRepository teamRepository,
                                 CurrentUser currentUser, AuditService auditService,
                                 PermissionService permissionService, TenantGuard tenantGuard) {
        this.departmentRepository = departmentRepository;
        this.teamRepository = teamRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.permissionService = permissionService;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public List<Department> list() {
        return departmentRepository.findByOrganizationId(currentUser.organizationId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Department> get(@PathVariable Long id) {
        return departmentRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NoSuchElementException("department not found"));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody DepartmentRequest req) {
        User actor = currentUser.get();
        // H2: the feature table (DEPARTMENT: Super Admin only) was defined but never enforced
        permissionService.require(actor, Action.CREATE, ResourceType.DEPARTMENT, actor.getOrganizationId(), null);
        if (req.name == null || req.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        tenantGuard.requireUser(req.headId);
        tenantGuard.requireDepartment(req.parentId);

        Department dept = new Department();
        dept.setOrganizationId(actor.getOrganizationId());
        dept.setName(req.name.trim());
        dept.setDescription(req.description);
        dept.setHeadId(req.headId);
        dept.setParentId(req.parentId); // a brand-new department can't be anyone's ancestor, so no cycle check needed
        if (req.active != null) dept.setActive(req.active);
        departmentRepository.save(dept);
        auditService.log("create", "departments", dept.getId(), dept.getName(), null, dept);
        return ResponseEntity.status(201).body(dept);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody DepartmentRequest req) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.DEPARTMENT, actor.getOrganizationId(), null);
        Department existing = departmentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .orElse(null);
        if (existing == null) throw new NoSuchElementException("department not found");
        if (req.name != null && req.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name cannot be blank"));
        }
        tenantGuard.requireUser(req.headId);
        if (req.parentId != null) {
            tenantGuard.requireDepartment(req.parentId);
            if (createsCycle(id, req.parentId)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "parentId would create a cycle in the department hierarchy"));
            }
        }

        Department before = copyOf(existing);
        if (req.name != null) existing.setName(req.name.trim());
        if (req.description != null) existing.setDescription(req.description);
        if (req.headId != null) existing.setHeadId(req.headId);
        if (req.parentId != null) existing.setParentId(req.parentId);
        // absent "active" leaves the flag alone instead of resetting it to false (M3)
        if (req.active != null) existing.setActive(req.active);
        departmentRepository.save(existing);
        auditService.log("update", "departments", id, existing.getName(), before, existing);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.DEPARTMENT, actor.getOrganizationId(), null);
        Long orgId = actor.getOrganizationId();
        Department existing = departmentRepository.findByIdAndOrganizationId(id, orgId).orElse(null);
        if (existing == null) throw new NoSuchElementException("department not found");
        // M18: refuse to orphan child departments or teams that point at this one
        if (departmentRepository.existsByOrganizationIdAndParentId(orgId, id)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "department still has sub-departments; move or delete them first"));
        }
        if (teamRepository.existsByOrganizationIdAndDepartmentId(orgId, id)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "department still has teams assigned; reassign them first"));
        }
        departmentRepository.delete(existing);
        auditService.log("delete", "departments", id, existing.getName(), existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * M2: walking up from the proposed parent must never reach the department
     * itself (that includes parentId == id). The visited set also stops the
     * walk if pre-existing data already contains a loop.
     */
    private boolean createsCycle(Long deptId, Long proposedParentId) {
        Long orgId = currentUser.organizationId();
        Set<Long> visited = new HashSet<>();
        Long cursor = proposedParentId;
        while (cursor != null) {
            if (cursor.equals(deptId)) return true;
            if (!visited.add(cursor)) return true; // existing loop — refuse to extend it
            cursor = departmentRepository.findByIdAndOrganizationId(cursor, orgId)
                    .map(Department::getParentId).orElse(null);
        }
        return false;
    }

    private Department copyOf(Department d) {
        Department c = new Department();
        c.setId(d.getId());
        c.setOrganizationId(d.getOrganizationId());
        c.setName(d.getName());
        c.setDescription(d.getDescription());
        c.setHeadId(d.getHeadId());
        c.setParentId(d.getParentId());
        c.setActive(d.isActive());
        return c;
    }
}
