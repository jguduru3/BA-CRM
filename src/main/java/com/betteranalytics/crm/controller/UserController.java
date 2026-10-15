package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.dto.PasswordResetRequest;
import com.betteranalytics.crm.dto.UserCreateRequest;
import com.betteranalytics.crm.dto.UserUpdateRequest;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.UserRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.security.PasswordPolicy;
import com.betteranalytics.crm.service.AuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/users")
public class UserController {

    /**
     * Pragmatic shape check (local@domain.tld, no spaces) — not full RFC 5322.
     * Its job is to reject obvious garbage before it becomes someone's login id.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** Deliberately generic: must not reveal whether the address exists (possibly in another tenant). */
    private static final String EMAIL_UNAVAILABLE = "this email address cannot be used";

    /** Upper bound when walking reportsTo chains — far deeper than any real org chart. */
    private static final int MAX_CHAIN_DEPTH = 100;

    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository, CurrentUser currentUser,
                           AuditService auditService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<User> list() {
        return userRepository.findByOrganizationId(currentUser.organizationId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> get(@PathVariable Long id) {
        return find(id).map(ResponseEntity::ok).orElseThrow(() -> new NoSuchElementException("user not found"));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody UserCreateRequest req) {
        requireAdmin(currentUser.get());
        if (req.name == null || req.name.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        // M11: one canonical form for emails so "Bob@X.com" and "bob@x.com " can't
        // become two accounts (login looks emails up exactly).
        String email = normalizeEmail(req.email);
        if (email == null) return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        if (!EMAIL.matcher(email).matches()) return ResponseEntity.badRequest().body(Map.of("error", "email is not a valid address"));
        String pwError = PasswordPolicy.validate(req.password, email, req.name);
        if (pwError != null) return ResponseEntity.badRequest().body(Map.of("error", pwError));
        if (userRepository.existsByEmailIgnoreCase(email)) return ResponseEntity.status(409).body(Map.of("error", EMAIL_UNAVAILABLE));

        Role role;
        try {
            role = req.role != null ? Role.valueOf(req.role) : Role.EXECUTIVE;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "role must be one of " + Arrays.toString(Role.values())));
        }
        if (req.reportsToId != null) {
            // a brand-new user has no reports yet, so only the existence check applies (no cycle possible)
            ResponseEntity<?> err = validateReportsTo(req.reportsToId, null);
            if (err != null) return err;
        }

        User user = new User();
        user.setOrganizationId(currentUser.organizationId());
        user.setName(req.name.trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(req.password));
        user.setRole(role);
        user.setInitials(req.initials != null ? req.initials : initialsOf(req.name));
        user.setColor(req.color != null ? req.color : "#0078d4");
        user.setReportsToId(req.reportsToId);
        user.setTerritory(req.territory);
        user.setActive(true);
        // The admin chose this password, so the user must pick their own on first login.
        user.setMustChangePassword(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(Map.of("error", EMAIL_UNAVAILABLE));
        }
        auditService.log("create", "users", user.getId(), user.getName(), null, redacted(user));
        return ResponseEntity.status(201).body(user);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UserUpdateRequest req) {
        User actor = currentUser.get();
        // Any user can update their own profile (name, initials, color);
        // only SUPER_ADMIN can change another user's role, active state, reportsToId or territory.
        boolean isSelf = id.equals(actor.getId());
        boolean isAdmin = actor.getRole() == Role.SUPER_ADMIN;
        if (!isSelf && !isAdmin) {
            throw new AccessDeniedException("you can only edit your own profile unless you are a Super Admin");
        }
        if (!isAdmin && req.role != null) {
            throw new AccessDeniedException("only Super Admin can change user roles");
        }
        if (!isAdmin && req.reportsToId != null) {
            throw new AccessDeniedException("only Super Admin can change the reporting line");
        }
        if (!isAdmin && req.active != null) {
            throw new AccessDeniedException("only Super Admin can activate or deactivate users");
        }
        // M10: territory drives Field Sales lead-claim routing — a rep editing their
        // own territory could claim any region's leads.
        if (!isAdmin && req.territory != null) {
            throw new AccessDeniedException("only Super Admin can change territories");
        }
        if (req.name != null && req.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name cannot be blank"));
        }

        User existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("user not found");

        Role newRole = null;
        if (req.role != null) {
            try {
                newRole = Role.valueOf(req.role);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "role must be one of " + Arrays.toString(Role.values())));
            }
        }
        boolean roleChanging = newRole != null && newRole != existing.getRole();
        boolean deactivating = req.active != null && !req.active && existing.isActive();

        // M8: an admin demoting themselves is almost always a mistake and can strand
        // the tenant with no Super Admin; role changes must be made by another admin.
        if (roleChanging && isSelf) {
            return ResponseEntity.badRequest().body(Map.of("error", "you cannot change your own role"));
        }
        if (deactivating && isSelf) {
            return ResponseEntity.badRequest().body(Map.of("error", "you cannot deactivate your own account"));
        }
        // M8: never leave the organization without an active Super Admin.
        if (existing.getRole() == Role.SUPER_ADMIN && existing.isActive() && (roleChanging || deactivating)
                && userRepository.countByOrganizationIdAndRoleAndActiveTrue(
                        existing.getOrganizationId(), Role.SUPER_ADMIN) <= 1) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "this is the last active Super Admin; promote another user first"));
        }

        if (req.reportsToId != null) {
            ResponseEntity<?> err = validateReportsTo(req.reportsToId, id);
            if (err != null) return err;
        }

        Map<String, Object> before = redacted(existing);

        if (req.name != null) existing.setName(req.name.trim());
        if (newRole != null) existing.setRole(newRole);
        if (req.initials != null) existing.setInitials(req.initials);
        if (req.color != null) existing.setColor(req.color);
        if (req.reportsToId != null) existing.setReportsToId(req.reportsToId);
        if (req.territory != null) existing.setTerritory(req.territory);
        // Deactivation is intentionally the ONLY side effect here — no
        // reassignment of the user's leads/tasks/opportunities. That's a
        // deliberate, separate, auditable action, not something that should
        // happen silently as a side effect of a role/profile edit.
        if (req.active != null) existing.setActive(req.active);

        // H16: a role change or deactivation must take effect immediately, not when
        // the user's current JWT happens to expire — bumping tokenVersion revokes
        // every token issued before this moment.
        if (roleChanging || deactivating) bumpTokenVersion(existing);

        existing.setUpdatedAt(Instant.now());
        userRepository.save(existing);
        auditService.log("update", "users", id, existing.getName(), before, redacted(existing));
        return ResponseEntity.ok(existing);
    }

    // Dedicated action for the one case a plain PUT can't express: clearing
    // reportsToId back to null (e.g. promoting someone to VP_SALES, who sits
    // at the top of the sales tree and reports to nobody). See the comment
    // on UserUpdateRequest.reportsToId for why this isn't just "pass null".
    @PostMapping("/{id}/clear-reports-to")
    public ResponseEntity<?> clearReportsTo(@PathVariable Long id) {
        requireAdmin(currentUser.get());
        User existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("user not found");
        Map<String, Object> before = redacted(existing);
        existing.setReportsToId(null);
        existing.setUpdatedAt(Instant.now());
        userRepository.save(existing);
        auditService.log("update", "users", id, existing.getName(), before, redacted(existing));
        return ResponseEntity.ok(existing);
    }

    /**
     * Self-service password change (id == caller) or Super Admin reset of
     * someone else's password.
     *
     * H16:
     *  - self-change requires currentPassword, so a hijacked session alone can't
     *    lock the real owner out;
     *  - every password set bumps tokenVersion, revoking all previously issued
     *    JWTs (including the caller's own on self-change — the client must sign
     *    in again);
     *  - an admin reset sets mustChangePassword=true (the admin knows the new
     *    password); a self-change clears it.
     */
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody PasswordResetRequest req) {
        User actor = currentUser.get();
        boolean isSelf = id.equals(actor.getId());
        // Users may reset their own password; only SUPER_ADMIN can reset others'.
        if (!isSelf && actor.getRole() != Role.SUPER_ADMIN) {
            throw new AccessDeniedException("only Super Admin can reset another user's password");
        }
        User existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("user not found");

        if (isSelf) {
            if (req.currentPassword == null || req.currentPassword.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "currentPassword is required"));
            }
            if (!passwordEncoder.matches(req.currentPassword, existing.getPassword())) {
                return ResponseEntity.badRequest().body(Map.of("error", "current password is incorrect"));
            }
        }
        String pwError = PasswordPolicy.validate(req.newPassword, existing.getEmail(), existing.getName());
        if (pwError != null) return ResponseEntity.badRequest().body(Map.of("error", pwError));
        if (isSelf && passwordEncoder.matches(req.newPassword, existing.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "new password must differ from the current one"));
        }

        existing.setPassword(passwordEncoder.encode(req.newPassword));
        existing.setMustChangePassword(!isSelf);
        bumpTokenVersion(existing);
        existing.setUpdatedAt(Instant.now());
        userRepository.save(existing);
        // audit the fact a reset happened — never the password itself, before or after
        auditService.log("update", "users", id, existing.getName(), null,
                Map.of("passwordReset", true, "selfService", isSelf));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── helpers ──────────────────────────────────────────────
    private Optional<User> find(Long id) {
        return userRepository.findByIdAndOrganizationId(id, currentUser.organizationId());
    }

    /** Invalidates every JWT issued for this user so far (tokens embed the version). */
    private static void bumpTokenVersion(User u) {
        u.setTokenVersion(u.getTokenVersion() + 1);
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) return null;
        String e = raw.trim().toLowerCase(Locale.ROOT);
        return e.isEmpty() ? null : e;
    }

    /**
     * reportsToId must point at a real user in the same org, can't point at
     * yourself, and (M9) can't create a loop: walking up the chain from the new
     * manager must never reach the user being edited. A cycle would make every
     * "everyone under me" walk in the permission engine either loop or grant
     * mutual team scope.
     */
    private ResponseEntity<?> validateReportsTo(Long reportsToId, Long selfId) {
        if (selfId != null && reportsToId.equals(selfId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "a user cannot report to themselves"));
        }
        Long orgId = currentUser.organizationId();
        Optional<User> target = userRepository.findByIdAndOrganizationId(reportsToId, orgId);
        if (target.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "reportsToId must be a user in this organization"));
        }
        if (selfId != null) {
            Set<Long> visited = new HashSet<>();
            Long cursor = target.get().getReportsToId();
            int depth = 0;
            while (cursor != null) {
                if (cursor.equals(selfId) || !visited.add(cursor) || ++depth > MAX_CHAIN_DEPTH) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "reportsToId would create a cycle in the reporting line"));
                }
                cursor = userRepository.findByIdAndOrganizationId(cursor, orgId)
                        .map(User::getReportsToId).orElse(null);
            }
        }
        return null;
    }

    private String initialsOf(String name) {
        String[] parts = name.trim().split("\\s+");
        String a = parts.length > 0 && !parts[0].isEmpty() ? parts[0].substring(0, 1) : "";
        String b = parts.length > 1 && !parts[parts.length - 1].isEmpty() ? parts[parts.length - 1].substring(0, 1) : "";
        return (a + b).toUpperCase();
    }

    private void requireAdmin(User actor) {
        if (actor.getRole() != Role.SUPER_ADMIN) {
            throw new AccessDeniedException("only Super Admin can perform this action");
        }
    }

    // Never put password (hash or otherwise) into an audit snapshot.
    private Map<String, Object> redacted(User u) {
        return Map.of(
                "id", u.getId(),
                "name", u.getName(),
                "email", u.getEmail(),
                "role", u.getRole(),
                "active", u.isActive(),
                "reportsToId", u.getReportsToId() == null ? "" : u.getReportsToId(),
                "territory", u.getTerritory() == null ? "" : u.getTerritory()
        );
    }
}
