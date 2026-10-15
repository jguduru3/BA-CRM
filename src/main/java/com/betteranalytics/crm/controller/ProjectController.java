package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.Paging;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Projects CRUD + members + milestones
 *
 * GET    /api/projects                 ?status=&ownerId=&q=&sort=&page=&size=
 * GET    /api/projects/summary         ?ids=1,2,3  — progress/member/task counts (grouped queries, no N+1)
 * POST   /api/projects
 * GET    /api/projects/{id}
 * PUT    /api/projects/{id}            — partial update: only the keys sent change
 * DELETE /api/projects/{id}            — soft delete; linked tasks are unlinked
 *
 * Members:
 *   GET    /api/projects/{id}/members
 *   POST   /api/projects/{id}/members       body: {userId, role}
 *   DELETE /api/projects/{id}/members/{uid}
 *
 * Milestones:
 *   GET    /api/projects/{id}/milestones
 *   POST   /api/projects/{id}/milestones
 *   PUT    /api/projects/{id}/milestones/{mid}
 *   DELETE /api/projects/{id}/milestones/{mid}
 *
 * Access: PermissionService decides (tenant → PROJECT feature → record scope on
 * the project owner). Project membership is layered on top: members with role
 * owner/manager may also edit the project, and any non-viewer member may change
 * milestones — but only if their role has the PROJECT edit feature at all
 * (SUPPORT never writes). Deleting and changing membership/ownership need
 * EDIT/DELETE scope on the owner (owner for reps, anyone for Manager+).
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Set<String> STATUSES = Set.of("active", "on_hold", "completed", "cancelled");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final Set<String> MEMBER_ROLES = Set.of("owner", "manager", "member", "viewer");
    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "name", "dueDate", "status", "priority");

    private final ProjectRepository projectRepo;
    private final ProjectMemberRepository memberRepo;
    private final ProjectMilestoneRepository milestoneRepo;
    private final UserRepository userRepo;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ProjectController(ProjectRepository projectRepo,
                              ProjectMemberRepository memberRepo,
                              ProjectMilestoneRepository milestoneRepo,
                              UserRepository userRepo,
                              CurrentUser currentUser,
                              PermissionService permissionService,
                              AuditService auditService,
                              ObjectMapper objectMapper) {
        this.projectRepo = projectRepo;
        this.memberRepo = memberRepo;
        this.milestoneRepo = milestoneRepo;
        this.userRepo = userRepo;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ── Projects ──────────────────────────────────────────────────────────────

    @GetMapping
    public List<Project> list(@RequestParam(required = false) String status,
                              @RequestParam(required = false) Long ownerId,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) String sort,
                              @RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.PROJECT, actor.getOrganizationId(), actor.getId());
        Long orgId = actor.getOrganizationId();
        Specification<Project> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("organizationId"), orgId));
            if (status != null && !status.isBlank()) ps.add(cb.equal(root.get("status"), status));
            if (ownerId != null) ps.add(cb.equal(root.get("ownerId"), ownerId));
            if (q != null && !q.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("name")), "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%", '\\'));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return projectRepo.findAll(spec, Paging.of(page, size, 500, 500, parseSort(sort))).getContent();
    }

    /**
     * Card statistics for the given (or all visible) projects, computed with three
     * grouped queries instead of one milestone/member lookup per project.
     */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) List<Long> ids) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.PROJECT, actor.getOrganizationId(), actor.getId());
        Long orgId = actor.getOrganizationId();
        Collection<Long> projectIds = ids != null && !ids.isEmpty()
                ? ids.stream().limit(500).toList()
                : projectRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream().limit(500).map(Project::getId).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        if (projectIds.isEmpty()) return out;
        Map<Long, Map<String, Object>> stats = new LinkedHashMap<>();
        for (Long id : projectIds) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("milestones", 0L); s.put("milestonesDone", 0L);
            s.put("members", 0L); s.put("tasks", 0L); s.put("tasksDone", 0L);
            s.put("memberIds", new ArrayList<Long>());
            stats.put(id, s);
        }
        for (Object[] r : milestoneRepo.progressByProject(orgId, projectIds)) {
            Map<String, Object> s = stats.get(((Number) r[0]).longValue());
            if (s == null) continue;
            s.put("milestones", ((Number) r[1]).longValue());
            s.put("milestonesDone", r[2] == null ? 0L : ((Number) r[2]).longValue());
        }
        for (Object[] r : projectRepo.taskCountsByProject(orgId, projectIds)) {
            Map<String, Object> s = stats.get(((Number) r[0]).longValue());
            if (s == null) continue;
            s.put("tasks", ((Number) r[1]).longValue());
            s.put("tasksDone", r[2] == null ? 0L : ((Number) r[2]).longValue());
        }
        for (ProjectMember m : memberRepo.findByOrganizationIdAndProjectIdIn(orgId, projectIds)) {
            Map<String, Object> s = stats.get(m.getProjectId());
            if (s == null) continue;
            @SuppressWarnings("unchecked") List<Long> memberIds = (List<Long>) s.get("memberIds");
            memberIds.add(m.getUserId());
            s.put("members", (long) memberIds.size());
        }
        stats.forEach((id, s) -> out.put(String.valueOf(id), s));
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        Project p = findViewable(id);
        if (p == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "project", p,
                "members", memberRepo.findByProjectId(id),
                "milestones", milestoneRepo.findByProjectIdOrderByPosition(id)
        ));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.PROJECT, actor.getOrganizationId(), null);
        Project body = bind(raw, Project.class);
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        if (body.getStatus() != null && !STATUSES.contains(body.getStatus())) return badRequest("invalid status");
        if (body.getPriority() != null && !PRIORITIES.contains(body.getPriority())) return badRequest("invalid priority");
        Long ownerId = body.getOwnerId() != null ? body.getOwnerId() : actor.getId();
        if (!userInOrg(ownerId, actor.getOrganizationId())) return badRequest("owner not found in your organization");
        if (!permissionService.canAssignTo(actor, ownerId)) {
            permissionService.require(actor, Action.EDIT, ResourceType.PROJECT, actor.getOrganizationId(), ownerId);
        }

        Project p = new Project();
        p.setOrganizationId(actor.getOrganizationId());
        p.setName(body.getName().trim());
        p.setDescription(body.getDescription());
        p.setStatus(body.getStatus() != null ? body.getStatus() : "active");
        p.setPriority(body.getPriority() != null ? body.getPriority() : "normal");
        p.setOwnerId(ownerId);
        p.setStartDate(body.getStartDate());
        p.setDueDate(body.getDueDate());
        p.setBudget(body.getBudget());
        p.setTags(body.getTags());
        p.setCoverColor(body.getCoverColor());
        p.setIcon(body.getIcon());
        if ("completed".equals(p.getStatus())) p.setCompletedAt(Instant.now());
        p.setCreatedBy(actor.getId());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        Project saved = projectRepo.save(p);

        // Auto-add creator (and the designated owner, if different) as owner members
        addMemberRow(saved, actor.getId(), "owner");
        if (!ownerId.equals(actor.getId())) addMemberRow(saved, ownerId, "owner");

        auditService.log("create", "projects", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        Project existing = findViewable(id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanEdit(actor, existing);
        Project body = bind(raw, Project.class);
        // Project has field defaults (status/priority) — only keys actually sent count.
        String newStatus = raw.containsKey("status") ? body.getStatus() : null;
        String newPriority = raw.containsKey("priority") ? body.getPriority() : null;
        if (newStatus != null && !STATUSES.contains(newStatus)) return badRequest("invalid status");
        if (newPriority != null && !PRIORITIES.contains(newPriority)) return badRequest("invalid priority");
        if (body.getOwnerId() != null && !body.getOwnerId().equals(existing.getOwnerId())) {
            permissionService.require(actor, Action.EDIT, ResourceType.PROJECT, existing.getOrganizationId(), existing.getOwnerId());
            if (!userInOrg(body.getOwnerId(), actor.getOrganizationId())) return badRequest("owner not found in your organization");
            existing.setOwnerId(body.getOwnerId());
            if (memberRepo.findByProjectIdAndUserId(id, body.getOwnerId()).isEmpty()) {
                addMemberRow(existing, body.getOwnerId(), "owner");
            }
        }
        if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName().trim());
        if (raw.containsKey("description")) existing.setDescription(body.getDescription());
        if (newStatus   != null) existing.setStatus(newStatus);
        if (newPriority != null) existing.setPriority(newPriority);
        if (raw.containsKey("startDate")) existing.setStartDate(body.getStartDate());
        if (raw.containsKey("dueDate"))   existing.setDueDate(body.getDueDate());
        if (raw.containsKey("budget"))    existing.setBudget(body.getBudget());
        if (raw.containsKey("tags"))      existing.setTags(body.getTags());
        if (body.getCoverColor()  != null) existing.setCoverColor(body.getCoverColor());
        if (body.getIcon()        != null) existing.setIcon(body.getIcon());
        if ("completed".equals(newStatus) && existing.getCompletedAt() == null) {
            existing.setCompletedAt(Instant.now());
        } else if (newStatus != null && !"completed".equals(newStatus)) {
            existing.setCompletedAt(null);
        }
        existing.setUpdatedAt(Instant.now());
        Project saved = projectRepo.save(existing);
        auditService.log("update", "projects", id, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    /**
     * Soft delete (the project disappears from every list and lookup). Its
     * milestones and members stay attached to the hidden row; tasks are
     * independent work, so they are unlinked rather than blocking the delete.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        Project existing = findViewable(id);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.PROJECT, existing.getOrganizationId(), existing.getOwnerId());
        int unlinked = projectRepo.unlinkTasks(actor.getOrganizationId(), id);
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(actor.getId());
        projectRepo.save(existing);
        auditService.log("delete", "projects", id, existing.getName(), existing, Map.of("unlinkedTasks", unlinked));
        return ResponseEntity.ok(Map.of("ok", true, "unlinkedTasks", unlinked));
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/members")
    public ResponseEntity<List<ProjectMember>> getMembers(@PathVariable Long id) {
        if (findViewable(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(memberRepo.findByProjectId(id));
    }

    @PostMapping("/{id}/members")
    @Transactional
    public ResponseEntity<?> addMember(@PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        Project p = findViewable(id);
        if (p == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId());

        if (body == null || !(body.get("userId") instanceof Number n)) return badRequest("userId must be a number");
        Long userId = n.longValue();
        Object roleObj = body.get("role");
        if (roleObj != null && !(roleObj instanceof String)) return badRequest("role must be a string");
        String role = roleObj != null ? (String) roleObj : "member";
        if (!MEMBER_ROLES.contains(role)) return badRequest("role must be one of owner, manager, member, viewer");
        if (!userInOrg(userId, actor.getOrganizationId())) return badRequest("user not found in your organization");

        if (memberRepo.findByProjectIdAndUserId(id, userId).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "user is already a member of this project"));
        }
        ProjectMember saved = addMemberRow(p, userId, role);
        auditService.log("update", "projects", id, p.getName(), null, Map.of("memberAdded", userId, "role", role));
        return ResponseEntity.status(201).body(saved);
    }

    @DeleteMapping("/{id}/members/{uid}")
    @Transactional
    public ResponseEntity<?> removeMember(@PathVariable Long id, @PathVariable Long uid) {
        User actor = currentUser.get();
        Project p = findViewable(id);
        if (p == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId());
        if (uid.equals(p.getOwnerId())) {
            return ResponseEntity.status(409).body(Map.of("error", "transfer ownership before removing the project owner"));
        }
        memberRepo.deleteByProjectIdAndUserId(id, uid);
        auditService.log("update", "projects", id, p.getName(), Map.of("memberRemoved", uid), null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Milestones ────────────────────────────────────────────────────────────

    @GetMapping("/{id}/milestones")
    public ResponseEntity<List<ProjectMilestone>> getMilestones(@PathVariable Long id) {
        if (findViewable(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(milestoneRepo.findByProjectIdOrderByPosition(id));
    }

    @PostMapping("/{id}/milestones")
    @Transactional
    public ResponseEntity<?> addMilestone(@PathVariable Long id,
                                          @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        Project p = findViewable(id);
        if (p == null) return ResponseEntity.notFound().build();
        requireCanContribute(actor, p);
        ProjectMilestone body = bind(raw, ProjectMilestone.class);
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        ProjectMilestone m = new ProjectMilestone();
        m.setProjectId(id);
        m.setOrganizationId(p.getOrganizationId());
        m.setName(body.getName().trim());
        m.setDescription(body.getDescription());
        m.setDueDate(body.getDueDate());
        m.setPosition(body.getPosition());
        m.setCompleted(body.isCompleted());
        if (body.isCompleted()) m.setCompletedAt(Instant.now());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        ProjectMilestone saved = milestoneRepo.save(m);
        auditService.log("create", "project_milestones", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}/milestones/{mid}")
    @Transactional
    public ResponseEntity<?> updateMilestone(@PathVariable Long id,
                                             @PathVariable Long mid,
                                             @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        Project p = findViewable(id);
        if (p == null) return ResponseEntity.notFound().build();
        requireCanContribute(actor, p);
        ProjectMilestone existing = findMilestone(id, mid);
        if (existing == null) return ResponseEntity.notFound().build();
        ProjectMilestone body = bind(raw, ProjectMilestone.class);
        if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName().trim());
        if (raw.containsKey("description")) existing.setDescription(body.getDescription());
        if (raw.containsKey("dueDate"))  existing.setDueDate(body.getDueDate());
        if (raw.containsKey("position")) existing.setPosition(body.getPosition());
        if (raw.containsKey("completed")) {
            boolean wasCompleted = existing.isCompleted();
            existing.setCompleted(body.isCompleted());
            if (body.isCompleted() && !wasCompleted) existing.setCompletedAt(Instant.now());
            if (!body.isCompleted()) existing.setCompletedAt(null);
        }
        existing.setUpdatedAt(Instant.now());
        ProjectMilestone saved = milestoneRepo.save(existing);
        auditService.log("update", "project_milestones", mid, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}/milestones/{mid}")
    @Transactional
    public ResponseEntity<?> deleteMilestone(@PathVariable Long id, @PathVariable Long mid) {
        User actor = currentUser.get();
        Project p = findViewable(id);
        if (p == null) return ResponseEntity.notFound().build();
        requireCanContribute(actor, p);
        ProjectMilestone existing = findMilestone(id, mid);
        if (existing == null) return ResponseEntity.notFound().build();
        milestoneRepo.delete(existing);
        auditService.log("delete", "project_milestones", mid, existing.getName(), existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Org-scoped lookup + VIEW check; another org's project is a 404. */
    private Project findViewable(Long id) {
        User actor = currentUser.get();
        Project p = projectRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (p == null) return null;
        permissionService.require(actor, Action.VIEW, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId());
        return p;
    }

    /** Milestone must be in the caller's org AND belong to the project in the path. */
    private ProjectMilestone findMilestone(Long projectId, Long milestoneId) {
        return milestoneRepo.findByIdAndOrganizationId(milestoneId, currentUser.organizationId())
                .filter(m -> Objects.equals(m.getProjectId(), projectId))
                .orElse(null);
    }

    private ProjectMember addMemberRow(Project p, Long userId, String role) {
        ProjectMember m = new ProjectMember();
        m.setProjectId(p.getId());
        m.setOrganizationId(p.getOrganizationId());
        m.setUserId(userId);
        m.setRole(role);
        m.setJoinedAt(Instant.now());
        return memberRepo.save(m);
    }

    private boolean userInOrg(Long userId, Long orgId) {
        return userId != null && userRepo.findByIdAndOrganizationId(userId, orgId).isPresent();
    }

    private String memberRole(User actor, Project p) {
        return memberRepo.findByProjectIdAndUserId(p.getId(), actor.getId()).map(ProjectMember::getRole).orElse(null);
    }

    private boolean hasEditFeature(User actor) {
        return permissionService.can(actor, Action.EDIT, ResourceType.PROJECT, actor.getOrganizationId(), actor.getId());
    }

    private void requireCanEdit(User actor, Project p) {
        if (permissionService.can(actor, Action.EDIT, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId())) return;
        String role = memberRole(actor, p);
        if (hasEditFeature(actor) && ("owner".equals(role) || "manager".equals(role))) return;
        permissionService.require(actor, Action.EDIT, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId());
    }

    private void requireCanContribute(User actor, Project p) {
        if (permissionService.can(actor, Action.EDIT, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId())) return;
        String role = memberRole(actor, p);
        if (hasEditFeature(actor) && role != null && !"viewer".equals(role)) return;
        permissionService.require(actor, Action.EDIT, ResourceType.PROJECT, p.getOrganizationId(), p.getOwnerId());
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) throw new IllegalArgumentException("cannot sort by " + field);
        Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(new Sort.Order(dir, field), Sort.Order.desc("id"));
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private <T> T bind(Map<String, Object> raw, Class<T> type) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
