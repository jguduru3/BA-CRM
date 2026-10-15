package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Delegation;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.Team;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.repository.DelegationRepository;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/delegations")
public class DelegationController {

    private final DelegationRepository delegationRepository;
    private final UserRepository        userRepository;
    private final CurrentUser           currentUser;
    private final AuditService          auditService;
    private final PermissionService     permissionService;
    private final NotificationService   notificationService;
    private final TenantGuard           tenantGuard;

    public DelegationController(DelegationRepository delegationRepository,
                                 UserRepository userRepository,
                                 CurrentUser currentUser,
                                 AuditService auditService,
                                 PermissionService permissionService,
                                 NotificationService notificationService,
                                 TenantGuard tenantGuard) {
        this.delegationRepository = delegationRepository;
        this.userRepository       = userRepository;
        this.currentUser          = currentUser;
        this.auditService         = auditService;
        this.permissionService    = permissionService;
        this.notificationService  = notificationService;
        this.tenantGuard          = tenantGuard;
    }

    /**
     * List active delegations visible to the current actor.
     * SUPER_ADMIN/VP_SALES: all org delegations.
     * MANAGER: only delegations they created.
     * Reps: only delegations where they are the delegate.
     */
    @GetMapping
    public List<Delegation> list(@RequestParam(defaultValue = "false") boolean includeExpired) {
        User actor = currentUser.get();
        Long orgId = currentUser.organizationId();
        List<Delegation> all = delegationRepository.findByOrganizationId(orgId);

        LocalDate today = LocalDate.now();
        return all.stream()
                .filter(d -> {
                    if (!includeExpired && d.getEndDate() != null && d.getEndDate().isBefore(today)) return false;
                    if (actor.getRole() == Role.SUPER_ADMIN || actor.getRole() == Role.VP_SALES) return true;
                    if (actor.getRole() == Role.MANAGER) return actor.getId().equals(d.getDelegatorId());
                    return actor.getId().equals(d.getDelegateId());
                })
                .toList();
    }

    /**
     * Create a delegation. Only MANAGER (or SUPER_ADMIN, VP_SALES) can delegate.
     * Spec rule: a Manager can delegate their team-scope to one of their reports
     * for a specified date range.
     *
     * Only delegateId / teamId / startDate / endDate are read from the body;
     * everything else (id, org, delegator, audit columns) is server-assigned.
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Delegation body) {
        User actor = currentUser.get();
        Role role = actor.getRole();
        boolean senior = role == Role.VP_SALES || role == Role.SUPER_ADMIN;

        if (role != Role.MANAGER && !senior) {
            throw new AccessDeniedException("only Managers and above can create delegations");
        }

        Long orgId = currentUser.organizationId();
        if (body.getDelegateId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "delegateId is required"));
        }
        // Validate delegate exists in same org
        Optional<User> delegateOpt = userRepository.findByIdAndOrganizationId(body.getDelegateId(), orgId);
        if (delegateOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "delegate not found in this organization"));
        }
        User delegate = delegateOpt.get();
        if (!delegate.isActive()) {
            return ResponseEntity.badRequest().body(Map.of("error", "delegate is not an active user"));
        }
        if (delegate.getId().equals(actor.getId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "you cannot delegate to yourself"));
        }

        if (body.getStartDate() == null || body.getEndDate() == null
                || body.getEndDate().isBefore(body.getStartDate())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "startDate and endDate are required and endDate must not be before startDate"));
        }

        // H3: a team restriction must name a team in this org that the delegator
        // actually leads — otherwise a Manager could grant a delegate access to
        // any team's records just by naming it. VP/Super Admin already have org
        // scope, so they may scope a delegation to any team in the org.
        if (body.getTeamId() != null) {
            Team team = tenantGuard.requireTeam(body.getTeamId());
            if (!senior && !permissionService.leadsTeam(actor.getId(), team)) {
                throw new AccessDeniedException("you can only delegate a team you lead");
            }
        }

        // H3: per spec a Manager delegates to "one of their reports" — the delegate
        // must report to them or be on a team they lead. Otherwise team scope could
        // be handed to an arbitrary user (e.g. a rep on a competing team).
        if (role == Role.MANAGER && !permissionService.isManagedBy(actor, delegate.getId())) {
            throw new AccessDeniedException("a Manager can only delegate to someone on their own team");
        }

        Delegation delegation = new Delegation();
        delegation.setOrganizationId(orgId);
        delegation.setDelegatorId(actor.getId());
        delegation.setDelegateId(delegate.getId());
        delegation.setTeamId(body.getTeamId());
        delegation.setStartDate(body.getStartDate());
        delegation.setEndDate(body.getEndDate());
        delegation.setCreatedBy(actor.getId());
        delegation.setCreatedAt(Instant.now());
        delegationRepository.save(delegation);
        auditService.log("create", "delegations", delegation.getId(),
                "delegate:" + delegate.getName(), null, delegation);
        notificationService.notify(orgId, delegation.getDelegateId(),
                "delegation_created",
                "You have been delegated authority by " + actor.getName(),
                "Delegation active from " + delegation.getStartDate() + " to " + delegation.getEndDate(),
                "Delegation", delegation.getId());
        return ResponseEntity.status(201).body(delegation);
    }

    /**
     * Cancel (delete) a delegation. SUPER_ADMIN can cancel any; MANAGER can
     * cancel only delegations they created.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        Delegation d = delegationRepository.findByIdAndOrganizationId(
                id, currentUser.organizationId()).orElse(null);
        if (d == null) throw new NoSuchElementException("delegation not found");

        User actor = currentUser.get();
        boolean isSuperAdmin = actor.getRole() == Role.SUPER_ADMIN;
        boolean isOwner      = actor.getId().equals(d.getDelegatorId());
        if (!isSuperAdmin && !isOwner) {
            throw new AccessDeniedException("you can only cancel delegations you created");
        }

        delegationRepository.delete(d);
        auditService.log("delete", "delegations", id, "delegation", d, Map.of("cancelled", true));
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
