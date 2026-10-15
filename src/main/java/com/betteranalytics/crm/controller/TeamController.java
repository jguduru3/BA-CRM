package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.dto.TeamMemberRequest;
import com.betteranalytics.crm.dto.TeamRequest;
import com.betteranalytics.crm.entity.Team;
import com.betteranalytics.crm.entity.TeamMember;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.DelegationRepository;
import com.betteranalytics.crm.repository.TeamMemberRepository;
import com.betteranalytics.crm.repository.TeamRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final DelegationRepository delegationRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final TenantGuard tenantGuard;
    private final PermissionService permissionService;

    public TeamController(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository,
                           UserRepository userRepository, DelegationRepository delegationRepository,
                           CurrentUser currentUser, AuditService auditService, TenantGuard tenantGuard,
                           PermissionService permissionService) {
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository = userRepository;
        this.delegationRepository = delegationRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.tenantGuard = tenantGuard;
        this.permissionService = permissionService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        require(Action.VIEW, null);
        List<Team> teams = teamRepository.findByOrganizationId(currentUser.organizationId());
        if (teams.isEmpty()) return List.of();

        // Batch: one roster query + one user query for the whole page instead of
        // several queries per team (M17).
        List<Long> teamIds = teams.stream().map(Team::getId).toList();
        Map<Long, List<TeamMember>> rosters = teamMemberRepository.findByTeamIdIn(teamIds).stream()
                .collect(Collectors.groupingBy(TeamMember::getTeamId));
        Set<Long> userIds = new HashSet<>();
        teams.forEach(t -> { if (t.getLeaderId() != null) userIds.add(t.getLeaderId()); });
        rosters.values().forEach(ms -> ms.forEach(m -> userIds.add(m.getUserId())));
        Map<Long, User> users = orgUsersById(userIds);

        return teams.stream()
                .map(t -> withMembers(t, rosters.getOrDefault(t.getId(), List.of()), users))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        require(Action.VIEW, null);
        return find(id).map(t -> ResponseEntity.ok(withMembers(t))).orElseThrow(() -> new NoSuchElementException("team not found"));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody TeamRequest req) {
        require(Action.CREATE, null);
        if (req.name == null || req.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        // leader/department must live in this tenant (H8)
        tenantGuard.requireUser(req.leaderId);
        tenantGuard.requireDepartment(req.departmentId);

        Team team = new Team();
        team.setOrganizationId(currentUser.organizationId());
        team.setName(req.name.trim());
        team.setDescription(req.description);
        team.setLeaderId(req.leaderId);
        team.setDepartmentId(req.departmentId);
        if (req.color != null) team.setColor(req.color);
        if (req.active != null) team.setActive(req.active);
        teamRepository.save(team);
        auditService.log("create", "teams", team.getId(), team.getName(), null, team);
        return ResponseEntity.status(201).body(team);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody TeamRequest req) {
        Team existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("team not found");
        require(Action.EDIT, existing.getLeaderId());
        if (req.name != null && req.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name cannot be blank"));
        }
        tenantGuard.requireUser(req.leaderId);
        tenantGuard.requireDepartment(req.departmentId);

        Team before = copyOf(existing);
        // null-safe partial merge — absent fields (including active) are left unchanged (M3)
        if (req.name != null) existing.setName(req.name.trim());
        if (req.description != null) existing.setDescription(req.description);
        if (req.leaderId != null) existing.setLeaderId(req.leaderId);
        if (req.departmentId != null) existing.setDepartmentId(req.departmentId);
        if (req.color != null) existing.setColor(req.color);
        if (req.active != null) existing.setActive(req.active);
        teamRepository.save(existing);
        auditService.log("update", "teams", id, existing.getName(), before, existing);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Team existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("team not found");
        require(Action.DELETE, existing.getLeaderId());
        // A current/future delegation scoped to this team would silently lose
        // its meaning (or, if the id were ever reused, point at another team).
        if (delegationRepository.existsByOrganizationIdAndTeamIdAndEndDateGreaterThanEqual(
                currentUser.organizationId(), id, LocalDate.now())) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "team is referenced by an active or upcoming delegation; cancel it first"));
        }
        // remove memberships first — team_members has no cascade/FK-delete configured
        teamMemberRepository.findByTeamId(id).forEach(teamMemberRepository::delete);
        teamRepository.delete(existing);
        auditService.log("delete", "teams", id, existing.getName(), existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Membership ───────────────────────────────────────────

    @GetMapping("/{id}/members")
    public ResponseEntity<?> members(@PathVariable Long id) {
        require(Action.VIEW, null);
        Team team = find(id).orElse(null);
        if (team == null) throw new NoSuchElementException("team not found");
        return ResponseEntity.ok(memberDetails(id));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable Long id, @RequestBody TeamMemberRequest req) {
        Team team = find(id).orElse(null);
        if (team == null) throw new NoSuchElementException("team not found");
        require(Action.EDIT, team.getLeaderId());
        if (req.userId == null) return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));

        // the user being added must belong to the same org — no cross-tenant membership
        tenantGuard.requireUser(req.userId);
        if (teamMemberRepository.findByTeamIdAndUserId(id, req.userId).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "user is already on this team"));
        }
        String role = req.role == null ? "MEMBER" : req.role.trim().toUpperCase(Locale.ROOT);
        if (!role.equals("MEMBER") && !role.equals("LEAD")) {
            return ResponseEntity.badRequest().body(Map.of("error", "role must be MEMBER or LEAD"));
        }

        TeamMember member = new TeamMember();
        member.setTeamId(id);
        member.setUserId(req.userId);
        member.setRole(role);
        teamMemberRepository.save(member);
        auditService.log("update", "teams", id, team.getName(), null, Map.of("addedMember", req.userId));
        return ResponseEntity.status(201).body(member);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<?> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        Team team = find(id).orElse(null);
        if (team == null) throw new NoSuchElementException("team not found");
        require(Action.EDIT, team.getLeaderId());
        TeamMember member = teamMemberRepository.findByTeamIdAndUserId(id, userId).orElse(null);
        if (member == null) throw new NoSuchElementException("team not found");
        teamMemberRepository.delete(member);
        auditService.log("update", "teams", id, team.getName(), null, Map.of("removedMember", userId));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── helpers ──────────────────────────────────────────────

    /**
     * Team rosters and leaders feed directly into PermissionService's TEAM scope,
     * so editing them is a privilege escalation path: a Manager who could add
     * people to their own team (or make themselves leader of another) would
     * widen their own data access. The TEAM rules in FeaturePermissionTable
     * therefore let Manager+ view rosters but only VP Sales / Super Admin
     * change them (H1). Unknown ids are resolved (404) before the check so
     * another tenant's ids are indistinguishable from missing ones.
     */
    private void require(Action action, Long leaderId) {
        User actor = currentUser.get();
        permissionService.require(actor, action, ResourceType.TEAM, actor.getOrganizationId(), leaderId);
    }

    private Optional<Team> find(Long id) {
        return teamRepository.findByIdAndOrganizationId(id, currentUser.organizationId());
    }

    /** Batch user lookup restricted to the caller's org — never resolves another tenant's users. */
    private Map<Long, User> orgUsersById(Collection<Long> ids) {
        if (ids.isEmpty()) return new HashMap<>(); // mutable: callers may get(null) for a missing leader
        Long orgId = currentUser.organizationId();
        return userRepository.findAllById(ids).stream()
                .filter(u -> orgId.equals(u.getOrganizationId()))
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Map<String, Object> withMembers(Team t) {
        List<TeamMember> roster = teamMemberRepository.findByTeamId(t.getId());
        Set<Long> userIds = roster.stream().map(TeamMember::getUserId).collect(Collectors.toCollection(HashSet::new));
        if (t.getLeaderId() != null) userIds.add(t.getLeaderId());
        return withMembers(t, roster, orgUsersById(userIds));
    }

    private Map<String, Object> withMembers(Team t, List<TeamMember> roster, Map<Long, User> users) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("leaderId", t.getLeaderId());
        m.put("leader", t.getLeaderId() == null ? null : userSummary(users.get(t.getLeaderId()))); // frontend renders t.leader as a full object
        m.put("departmentId", t.getDepartmentId());
        m.put("color", t.getColor());
        m.put("active", t.isActive());
        m.put("memberCount", roster.size());
        m.put("members", memberRows(roster, users)); // frontend renders t.members as an array, not just a count
        return m;
    }

    private Map<String, Object> userSummary(User u) {
        if (u == null) return null;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", u.getId());
        row.put("name", u.getName());
        row.put("initials", u.getInitials());
        row.put("color", u.getColor());
        return row;
    }

    private List<Map<String, Object>> memberDetails(Long teamId) {
        List<TeamMember> roster = teamMemberRepository.findByTeamId(teamId);
        return memberRows(roster, orgUsersById(roster.stream().map(TeamMember::getUserId).toList()));
    }

    private List<Map<String, Object>> memberRows(List<TeamMember> roster, Map<Long, User> users) {
        return roster.stream().map(tm -> {
            User u = users.get(tm.getUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tm.getUserId()); // frontend uses m.id as the React list key
            row.put("userId", tm.getUserId());
            row.put("role", tm.getRole());
            row.put("name", u != null ? u.getName() : null);
            row.put("email", u != null ? u.getEmail() : null);
            row.put("initials", u != null ? u.getInitials() : null);
            row.put("color", u != null ? u.getColor() : null);
            return row;
        }).collect(Collectors.toList());
    }

    private Team copyOf(Team t) {
        Team c = new Team();
        c.setId(t.getId());
        c.setOrganizationId(t.getOrganizationId());
        c.setName(t.getName());
        c.setDescription(t.getDescription());
        c.setLeaderId(t.getLeaderId());
        c.setDepartmentId(t.getDepartmentId());
        c.setColor(t.getColor());
        c.setActive(t.isActive());
        return c;
    }
}
