package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Task;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.TaskRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    /** Status value that means "finished" — drives completedAt (M4). */
    private static final String DONE = "Done";

    private final TaskRepository      taskRepository;
    private final CurrentUser         currentUser;
    private final AuditService        auditService;
    private final PermissionService   permissionService;
    private final NotificationService notificationService;
    private final TenantGuard         tenantGuard;

    public TaskController(TaskRepository taskRepository, CurrentUser currentUser,
                           AuditService auditService, PermissionService permissionService,
                           NotificationService notificationService, TenantGuard tenantGuard) {
        this.taskRepository     = taskRepository;
        this.currentUser        = currentUser;
        this.auditService       = auditService;
        this.permissionService  = permissionService;
        this.notificationService = notificationService;
        this.tenantGuard        = tenantGuard;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "200") int limit,
                                     @RequestParam(required = false) String relatedType,
                                     @RequestParam(required = false) Long relatedId) {
        User actor = currentUser.get();
        // Task's "owner" for scope purposes is assigneeId (field name differs from Lead/Opp,
        // but OWN/TEAM/ORG scope resolution is identical).
        List<Task> all = taskRepository.findByOrganizationId(currentUser.organizationId()).stream()
                .filter(t -> permissionService.can(actor, Action.VIEW, ResourceType.TASK,
                                                   t.getOrganizationId(), t.getAssigneeId()))
                .filter(t -> relatedType == null || relatedType.equalsIgnoreCase(t.getRelatedType()))
                .filter(t -> relatedId   == null || relatedId.equals(t.getRelatedId()))
                .collect(Collectors.toList());
        return Map.of("count", all.size(),
                      "data", all.stream().limit(TenantGuard.clampLimit(limit)).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> get(@PathVariable Long id) {
        Task task = find(id).orElse(null);
        if (task == null) throw new NoSuchElementException("task not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.TASK,
                                  task.getOrganizationId(), task.getAssigneeId());
        return ResponseEntity.ok(task);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Task body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.TASK, currentUser.organizationId(), null);
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        // H9 / M1: every foreign key must resolve inside this tenant
        tenantGuard.requireUser(body.getAssigneeId());
        tenantGuard.requireProject(body.getProjectId());
        String relatedType = tenantGuard.requireRelated(body.getRelatedType(), body.getRelatedId());

        // H12: the entity is bound straight from JSON — reset every server-owned column
        body.setId(null);
        body.setOrganizationId(currentUser.organizationId());
        body.setRelatedType(relatedType);
        body.setDeletedAt(null);
        body.setDeletedBy(null);
        body.setDeleteReason(null);
        if (body.getAssigneeId() == null) body.setAssigneeId(currentUser.userId());
        if (body.getPriority() == null) body.setPriority("Medium");
        if (body.getStatus() == null) body.setStatus("Todo");
        // completedAt is derived from status, not trusted from the client
        body.setCompletedAt(DONE.equals(body.getStatus()) ? Instant.now() : null);
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        taskRepository.save(body);
        auditService.log("create", "tasks", body.getId(), body.getTitle(), null, body);
        if (body.getAssigneeId() != null && !body.getAssigneeId().equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), body.getAssigneeId(),
                    "task_assigned", "Task assigned to you: " + body.getTitle(),
                    "Assigned by " + actor.getName(), "Task", body.getId());
        }
        return ResponseEntity.status(201).body(body);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Task body) {
        Task existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("task not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.TASK,
                                  existing.getOrganizationId(), existing.getAssigneeId());

        if (body.getTitle() != null && body.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title cannot be blank"));
        }
        // H10: reassigning is a scope change — an OWN-scope rep handing a task to
        // someone else would also hand away their own access to it. Manager+ only,
        // and Managers only within their team.
        boolean assigneeChanging = body.getAssigneeId() != null
                && !body.getAssigneeId().equals(existing.getAssigneeId());
        if (assigneeChanging) {
            tenantGuard.requireUser(body.getAssigneeId());
            if (!permissionService.canEditLeadOwner(actor)
                    || !permissionService.canAssignTo(actor, body.getAssigneeId())) {
                throw new AccessDeniedException("you are not permitted to reassign this task");
            }
        }
        tenantGuard.requireProject(body.getProjectId());
        String relatedType = existing.getRelatedType();
        // Only re-validate the link when it actually changes, so editing a task whose
        // legacy link predates validation doesn't suddenly fail.
        boolean relatedChanging =
                (body.getRelatedType() != null && !body.getRelatedType().equalsIgnoreCase(String.valueOf(existing.getRelatedType())))
                || (body.getRelatedId() != null && !body.getRelatedId().equals(existing.getRelatedId()));
        if (relatedChanging) {
            String type = body.getRelatedType() != null ? body.getRelatedType() : existing.getRelatedType();
            Long rid    = body.getRelatedId()   != null ? body.getRelatedId()   : existing.getRelatedId();
            relatedType = tenantGuard.requireRelated(type, rid);
        }

        Long previousAssigneeId = existing.getAssigneeId();
        String previousStatus = existing.getStatus();
        Task before = copyOf(existing);
        if (body.getTitle()      != null) existing.setTitle(body.getTitle());
        if (body.getPriority()   != null) existing.setPriority(body.getPriority());
        if (body.getStatus()     != null) existing.setStatus(body.getStatus());
        if (body.getAssigneeId() != null) existing.setAssigneeId(body.getAssigneeId());
        if (body.getDueDate()    != null) existing.setDueDate(body.getDueDate());
        if (body.getProjectId()  != null) existing.setProjectId(body.getProjectId());
        if (body.getRelatedId()  != null) existing.setRelatedId(body.getRelatedId());
        existing.setRelatedType(relatedType);

        // M4: completedAt used to be overwritten with whatever the body had — a
        // partial update without it wiped the completion time. Now it follows the
        // status transition; an explicit completedAt from the client still wins.
        boolean nowDone = DONE.equals(existing.getStatus());
        boolean wasDone = DONE.equals(previousStatus);
        if (body.getCompletedAt() != null) {
            existing.setCompletedAt(body.getCompletedAt());
        } else if (nowDone && !wasDone) {
            existing.setCompletedAt(Instant.now());
        } else if (!nowDone && wasDone) {
            existing.setCompletedAt(null);
        }
        existing.setUpdatedAt(Instant.now());
        taskRepository.save(existing);
        auditService.log("update", "tasks", id, existing.getTitle(), before, existing);
        Long newAssigneeId = existing.getAssigneeId();
        if (newAssigneeId != null && !newAssigneeId.equals(previousAssigneeId)
                && !newAssigneeId.equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), newAssigneeId,
                    "task_assigned", "Task assigned to you: " + existing.getTitle(),
                    "Reassigned by " + actor.getName(), "Task", existing.getId());
        }
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Task existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("task not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.TASK,
                                  existing.getOrganizationId(), existing.getAssigneeId());
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
        Task existing = taskRepository.findByIdAndOrganizationIdIncludingDeleted(
                id, currentUser.organizationId()).orElse(null);
        if (existing == null) throw new NoSuchElementException("task not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.TASK,
                                  existing.getOrganizationId(), existing.getAssigneeId());
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

    private Optional<Task> find(Long id) {
        return taskRepository.findByIdAndOrganizationId(id, currentUser.organizationId());
    }

    private Task copyOf(Task t) {
        Task c = new Task();
        c.setId(t.getId()); c.setOrganizationId(t.getOrganizationId()); c.setTitle(t.getTitle());
        c.setPriority(t.getPriority()); c.setStatus(t.getStatus()); c.setAssigneeId(t.getAssigneeId());
        c.setDueDate(t.getDueDate()); c.setCompletedAt(t.getCompletedAt());
        c.setRelatedType(t.getRelatedType()); c.setRelatedId(t.getRelatedId());
        c.setProjectId(t.getProjectId());
        c.setCreatedAt(t.getCreatedAt()); c.setUpdatedAt(t.getUpdatedAt());
        return c;
    }
}
