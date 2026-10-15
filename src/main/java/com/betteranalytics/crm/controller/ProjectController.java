package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Projects CRUD + members + milestones
 *
 * GET    /api/projects
 * POST   /api/projects
 * GET    /api/projects/{id}
 * PUT    /api/projects/{id}
 * DELETE /api/projects/{id}
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
 * Access: every org member can read projects. Editing a project requires
 * being its owner, a project member with role owner/manager, or a Manager+.
 * Only the owner or a Manager+ can delete it or change its membership.
 * Milestones can be changed by anyone who can edit the project plus regular
 * "member"s (not "viewer"s).
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Set<String> STATUSES = Set.of("active", "on_hold", "completed", "cancelled");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final Set<String> MEMBER_ROLES = Set.of("owner", "manager", "member", "viewer");

    private final ProjectRepository projectRepo;
    private final ProjectMemberRepository memberRepo;
    private final ProjectMilestoneRepository milestoneRepo;
    private final UserRepository userRepo;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ProjectController(ProjectRepository projectRepo,
                              ProjectMemberRepository memberRepo,
                              ProjectMilestoneRepository milestoneRepo,
                              UserRepository userRepo,
                              CurrentUser currentUser,
                              AuditService auditService,
                              ObjectMapper objectMapper) {
        this.projectRepo = projectRepo;
        this.memberRepo = memberRepo;
        this.milestoneRepo = milestoneRepo;
        this.userRepo = userRepo;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ── Projects ──────────────────────────────────────────────────────────────

    @GetMapping
    public List<Project> list() {
        return projectRepo.findByOrganizationIdOrderByCreatedAtDesc(currentUser.organizationId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        Project p = find(id);
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
        Project body = bind(raw, Project.class);
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        if (body.getStatus() != null && !STATUSES.contains(body.getStatus())) return badRequest("invalid status");
        if (body.getPriority() != null && !PRIORITIES.contains(body.getPriority())) return badRequest("invalid priority");
        Long ownerId = body.getOwnerId() != null ? body.getOwnerId() : actor.getId();
        if (!userInOrg(ownerId, actor.getOrganizationId())) return badRequest("owner not found in your organization");

        Project p = new Project();
        p.setOrganizationId(actor.getOrganizationId());
        p.setName(body.getName());
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
        Project existing = find(id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanEdit(actor, existing);
        Project body = bind(raw, Project.class);
        if (body.getStatus() != null && !STATUSES.contains(body.getStatus())) return badRequest("invalid status");
        if (body.getPriority() != null && !PRIORITIES.contains(body.getPriority())) return badRequest("invalid priority");
        if (body.getOwnerId() != null && !body.getOwnerId().equals(existing.getOwnerId())) {
            requireOwnerOrManager(actor, existing);
            if (!userInOrg(body.getOwnerId(), actor.getOrganizationId())) return badRequest("owner not found in your organization");
            existing.setOwnerId(body.getOwnerId());
            if (memberRepo.findByProjectIdAndUserId(id, body.getOwnerId()).isEmpty()) {
                addMemberRow(existing, body.getOwnerId(), "owner");
            }
        }
        if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        if (body.getStatus()      != null) existing.setStatus(body.getStatus());
        if (body.getPriority()    != null) existing.setPriority(body.getPriority());
        if (raw.containsKey("startDate")) existing.setStartDate(body.getStartDate());
        if (raw.containsKey("dueDate"))   existing.setDueDate(body.getDueDate());
        if (raw.containsKey("budget"))    existing.setBudget(body.getBudget());
        if (body.getTags()        != null) existing.setTags(body.getTags());
        if (body.getCoverColor()  != null) existing.setCoverColor(body.getCoverColor());
        if (body.getIcon()        != null) existing.setIcon(body.getIcon());
        if ("completed".equals(body.getStatus()) && existing.getCompletedAt() == null) {
            existing.setCompletedAt(Instant.now());
        } else if (body.getStatus() != null && !"completed".equals(body.getStatus())) {
            existing.setCompletedAt(null);
        }
        existing.setUpdatedAt(Instant.now());
        Project saved = projectRepo.save(existing);
        auditService.log("update", "projects", id, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        Project existing = find(id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireOwnerOrManager(actor, existing);
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(actor.getId());
        projectRepo.save(existing);
        auditService.log("delete", "projects", id, existing.getName(), null, Map.of());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Members ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/members")
    public ResponseEntity<List<ProjectMember>> getMembers(@PathVariable Long id) {
        if (find(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(memberRepo.findByProjectId(id));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        Project p = find(id);
        if (p == null) return ResponseEntity.notFound().build();
        requireOwnerOrManager(actor, p);

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
        return ResponseEntity.status(201).body(addMemberRow(p, userId, role));
    }

    @DeleteMapping("/{id}/members/{uid}")
    @Transactional
    public ResponseEntity<?> removeMember(@PathVariable Long id, @PathVariable Long uid) {
        User actor = currentUser.get();
        Project p = find(id);
        if (p == null) return ResponseEntity.notFound().build();
        requireOwnerOrManager(actor, p);
        if (uid.equals(p.getOwnerId())) {
            return ResponseEntity.status(409).body(Map.of("error", "transfer ownership before removing the project owner"));
        }
        memberRepo.deleteByProjectIdAndUserId(id, uid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Milestones ────────────────────────────────────────────────────────────

    @GetMapping("/{id}/milestones")
    public ResponseEntity<List<ProjectMilestone>> getMilestones(@PathVariable Long id) {
        if (find(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(milestoneRepo.findByProjectIdOrderByPosition(id));
    }

    @PostMapping("/{id}/milestones")
    public ResponseEntity<?> addMilestone(@PathVariable Long id,
                                          @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        Project p = find(id);
        if (p == null) return ResponseEntity.notFound().build();
        requireCanContribute(actor, p);
        ProjectMilestone body = bind(raw, ProjectMilestone.class);
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        ProjectMilestone m = new ProjectMilestone();
        m.setProjectId(id);
        m.setOrganizationId(p.getOrganizationId());
        m.setName(body.getName());
        m.setDescription(body.getDescription());
        m.setDueDate(body.getDueDate());
        m.setPosition(body.getPosition());
        m.setCompleted(body.isCompleted());
        if (body.isCompleted()) m.setCompletedAt(Instant.now());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        return ResponseEntity.status(201).body(milestoneRepo.save(m));
    }

    @PutMapping("/{id}/milestones/{mid}")
    public ResponseEntity<?> updateMilestone(@PathVariable Long id,
                                             @PathVariable Long mid,
                                             @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        Project p = find(id);
        if (p == null) return ResponseEntity.notFound().build();
        requireCanContribute(actor, p);
        ProjectMilestone existing = findMilestone(id, mid);
        if (existing == null) return ResponseEntity.notFound().build();
        ProjectMilestone body = bind(raw, ProjectMilestone.class);
        if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        if (raw.containsKey("dueDate"))  existing.setDueDate(body.getDueDate());
        if (raw.containsKey("position")) existing.setPosition(body.getPosition());
        if (raw.containsKey("completed")) {
            boolean wasCompleted = existing.isCompleted();
            existing.setCompleted(body.isCompleted());
            if (body.isCompleted() && !wasCompleted) existing.setCompletedAt(Instant.now());
            if (!body.isCompleted()) existing.setCompletedAt(null);
        }
        existing.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(milestoneRepo.save(existing));
    }

    @DeleteMapping("/{id}/milestones/{mid}")
    public ResponseEntity<?> deleteMilestone(@PathVariable Long id, @PathVariable Long mid) {
        User actor = currentUser.get();
        Project p = find(id);
        if (p == null) return ResponseEntity.notFound().build();
        requireCanContribute(actor, p);
        ProjectMilestone existing = findMilestone(id, mid);
        if (existing == null) return ResponseEntity.notFound().build();
        milestoneRepo.delete(existing);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Project find(Long id) {
        return projectRepo.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
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

    private void requireOwnerOrManager(User actor, Project p) {
        if (!actor.getId().equals(p.getOwnerId()) && !isManagerPlus(actor)) {
            throw new AccessDeniedException("only the project owner or a manager can do this");
        }
    }

    private void requireCanEdit(User actor, Project p) {
        if (actor.getId().equals(p.getOwnerId()) || isManagerPlus(actor)) return;
        String role = memberRole(actor, p);
        if (!"owner".equals(role) && !"manager".equals(role)) {
            throw new AccessDeniedException("not permitted to edit this project");
        }
    }

    private void requireCanContribute(User actor, Project p) {
        if (actor.getId().equals(p.getOwnerId()) || isManagerPlus(actor)) return;
        String role = memberRole(actor, p);
        if (role == null || "viewer".equals(role)) {
            throw new AccessDeniedException("not permitted to change this project's milestones");
        }
    }

    private static boolean isManagerPlus(User u) {
        return u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES || u.getRole() == Role.MANAGER;
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
