package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.ListParams;
import com.betteranalytics.crm.core.Patch;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.entity.Task;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.TaskRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.TenantGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    /** Status value that means "finished" — drives completedAt (M4). */
    private static final String DONE = "Done";

    private static final Map<String, String> SORTABLE = Map.of(
            "title", "title", "status", "status", "priority", "priority", "dueDate", "dueDate",
            "createdAt", "createdAt", "updatedAt", "updatedAt", "id", "id");

    private final TaskRepository      taskRepository;
    private final CurrentUser         currentUser;
    private final AuditService        auditService;
    private final PermissionService   permissionService;
    private final NotificationService notificationService;
    private final TenantGuard         tenantGuard;

    public TaskController(TaskRepository taskRepository, CurrentUser currentUser,
                          AuditService auditService, PermissionService permissionService,
                          NotificationService notificationService, TenantGuard tenantGuard) {
        this.taskRepository      = taskRepository;
        this.currentUser         = currentUser;
        this.auditService        = auditService;
        this.permissionService   = permissionService;
        this.notificationService = notificationService;
        this.tenantGuard         = tenantGuard;
    }

    /** Task's "owner" for scope purposes is assigneeId. */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String search,
                                    @RequestParam(required = false) String relatedType,
                                    @RequestParam(required = false) Long relatedId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String priority,
                                    @RequestParam(required = false) Long assigneeId,
                                    @RequestParam(required = false) Long projectId,
                                    @RequestParam(defaultValue = "false") boolean overdue,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String sort) {
        User actor = currentUser.get();
        Specification<Task> spec = visibleSpec(actor)
                .and(Specs.search(search, "title"))
                .and(Specs.eqIgnoreCase("relatedType", relatedType))
                .and(Specs.eq("relatedId", relatedId))
                .and(Specs.eqIgnoreCase("status", status))
                .and(Specs.eqIgnoreCase("priority", priority))
                .and(Specs.eq("assigneeId", assigneeId))
                .and(Specs.eq("projectId", projectId));
        if (overdue) {
            LocalDate today = LocalDate.now();
            spec = spec.and((root, q, cb) -> cb.and(cb.lessThan(root.get("dueDate"), today),
                    cb.notEqual(root.get("status"), DONE)));
        }
        Page<Task> result = taskRepository.findAll(spec,
                ListParams.pageable(page, size, limit, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "updatedAt")));
        return ListParams.response(result, result.getContent());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> get(@PathVariable Long id) {
        Task task = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.TASK, task.getOrganizationId(), task.getAssigneeId());
        return ResponseEntity.ok(task);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody JsonNode body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.TASK, currentUser.organizationId(), null);
        Patch p = Patch.of(body);
        if (!p.hasValue("title")) throw new IllegalArgumentException("title is required");
        // H9 / M1: every foreign key must resolve inside this tenant
        Long assigneeId = p.lng("assigneeId");
        tenantGuard.requireUser(assigneeId);
        tenantGuard.requireProject(p.lng("projectId"));
        String relatedType = tenantGuard.requireRelated(p.str("relatedType"), p.lng("relatedId"));

        Task task = new Task();
        apply(task, p);
        task.setOrganizationId(currentUser.organizationId());
        task.setRelatedType(relatedType);
        task.setRelatedId(p.lng("relatedId"));
        task.setAssigneeId(assigneeId != null ? assigneeId : actor.getId());
        // completedAt is derived from status, not trusted from the client
        task.setCompletedAt(DONE.equals(task.getStatus()) ? Instant.now() : null);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);
        auditService.log("create", "tasks", task.getId(), task.getTitle(), null, task);
        if (!task.getAssigneeId().equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), task.getAssigneeId(),
                    "task_assigned", "Task assigned to you: " + task.getTitle(),
                    "Assigned by " + actor.getName(), "Task", task.getId());
        }
        return ResponseEntity.status(201).body(task);
    }

    /** Partial update (PUT and PATCH): only fields present in the body change. */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody JsonNode body) {
        Task existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.TASK, existing.getOrganizationId(), existing.getAssigneeId());
        Patch p = Patch.of(body);
        if (p.has("title") && !p.hasValue("title")) throw new IllegalArgumentException("title cannot be blank");
        // H10: reassigning is a scope change — Manager+ only, Managers within their team.
        Long newAssignee = p.lng("assigneeId");
        if (newAssignee != null && !newAssignee.equals(existing.getAssigneeId())) {
            tenantGuard.requireUser(newAssignee);
            if (!permissionService.canEditLeadOwner(actor) || !permissionService.canAssignTo(actor, newAssignee)) {
                throw new AccessDeniedException("you are not permitted to reassign this task");
            }
        }
        if (p.has("projectId") && !Objects.equals(p.lng("projectId"), existing.getProjectId())) {
            tenantGuard.requireProject(p.lng("projectId"));
        }
        // Only re-validate the link when it actually changes, so editing a task whose
        // legacy link predates validation doesn't suddenly fail.
        String relatedType = existing.getRelatedType();
        Long relatedId = existing.getRelatedId();
        boolean typeChanging = p.has("relatedType")
                && !Objects.equals(lower(blankToNull(p.str("relatedType"))), lower(existing.getRelatedType()));
        boolean idChanging = p.has("relatedId") && !Objects.equals(p.lng("relatedId"), existing.getRelatedId());
        boolean relatedChanging = typeChanging || idChanging;
        if (relatedChanging) {
            String type = p.has("relatedType") ? p.str("relatedType") : existing.getRelatedType();
            relatedId = p.has("relatedId") ? p.lng("relatedId") : existing.getRelatedId();
            relatedType = tenantGuard.requireRelated(type, relatedId);
        }

        Long previousAssigneeId = existing.getAssigneeId();
        String previousStatus = existing.getStatus();
        Task before = snapshot(existing);
        apply(existing, p);
        if (newAssignee != null) existing.setAssigneeId(newAssignee);
        if (relatedChanging) {
            existing.setRelatedType(relatedType);
            existing.setRelatedId(relatedId);
        }

        // M4: completedAt follows the status transition; an explicit value from the client wins.
        boolean nowDone = DONE.equals(existing.getStatus());
        boolean wasDone = DONE.equals(previousStatus);
        if (p.hasValue("completedAt")) {
            existing.setCompletedAt(p.instant("completedAt"));
        } else if (nowDone && !wasDone) {
            existing.setCompletedAt(Instant.now());
        } else if (!nowDone && wasDone) {
            existing.setCompletedAt(null);
        }
        existing.setUpdatedAt(Instant.now());
        taskRepository.save(existing);
        auditService.log("update", "tasks", id, existing.getTitle(), before, existing);
        Long newAssigneeId = existing.getAssigneeId();
        if (newAssigneeId != null && !newAssigneeId.equals(previousAssigneeId) && !newAssigneeId.equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), newAssigneeId,
                    "task_assigned", "Task assigned to you: " + existing.getTitle(),
                    "Reassigned by " + actor.getName(), "Task", existing.getId());
        }
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Task existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.TASK, existing.getOrganizationId(), existing.getAssigneeId());
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(currentUser.userId());
        existing.setDeleteReason(reason);
        taskRepository.save(existing);
        auditService.log("delete", "tasks", id, existing.getTitle(), existing,
                         Map.of("reason", reason == null ? "" : reason));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id) {
        Task existing = taskRepository.findByIdAndOrganizationIdIncludingDeleted(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("task not found"));
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.TASK, existing.getOrganizationId(), existing.getAssigneeId());
        existing.setDeletedAt(null);
        existing.setDeletedBy(null);
        existing.setDeleteReason(null);
        taskRepository.save(existing);
        auditService.log("update", "tasks", id, existing.getTitle(), null, Map.of("restored", true));
        return ResponseEntity.ok(existing);
    }

    @GetMapping("/trash")
    public List<Task> trash() {
        User actor = currentUser.get();
        return taskRepository.findTrashedByOrganizationId(currentUser.organizationId()).stream()
                .filter(t -> permissionService.can(actor, Action.DELETE, ResourceType.TASK,
                                                   t.getOrganizationId(), t.getAssigneeId()))
                .collect(Collectors.toList());
    }

    private Specification<Task> visibleSpec(User actor) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.TASK);
        if (scope.none()) throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW tasks");
        return Specs.<Task>org(actor.getOrganizationId()).and(Specs.ownedWithin(scope, "assigneeId"));
    }

    private Task find(Long id) {
        return taskRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("task not found"));
    }

    /** Client-editable fields; assignee, related link and completedAt are handled by the caller. */
    private void apply(Task t, Patch p) {
        p.requiredText("title", t::setTitle);
        p.requiredText("priority", t::setPriority);
        p.requiredText("status", t::setStatus);
        p.dateValue("dueDate", t::setDueDate);
        p.longValue("projectId", t::setProjectId);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }

    private static Task snapshot(Task t) {
        Task c = new Task();
        BeanUtils.copyProperties(t, c);
        return c;
    }
}
