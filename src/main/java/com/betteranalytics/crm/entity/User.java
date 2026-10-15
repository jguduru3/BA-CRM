package com.betteranalytics.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true, length = 200)
    private String email;

    @JsonIgnore
    @Column(nullable = false, length = 200)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role = Role.EXECUTIVE;

    // NEW — the reporting-tree primitive: "who does this user roll up to for
    // permission-scope purposes". MANAGER.reportsToId -> a VP_SALES user's id;
    // EXECUTIVE/INSIDE_SALES/FIELD_SALES.reportsToId -> their MANAGER's id;
    // VP_SALES.reportsToId is null (top of the sales tree); SUPER_ADMIN and
    // SUPPORT sit outside the tree and are never walked through this field.
    // Team/TeamMember (separate tables) remain the roster/grouping mechanism
    // (a Manager can run more than one Team; a rep can sit on more than one);
    // this field is specifically what PermissionService walks to compute
    // "everyone under me" for record-scope resolution.
    @Column(name = "reports_to_id")
    private Long reportsToId;

    // NEW — free-text territory tag for FIELD_SALES routing (e.g. matched
    // against Lead.geography). Deliberately a plain string, not a
    // state/city/pincode structure — see the Step 1 territory-definition
    // default. Meaningless for other roles, left null.
    @Column(length = 150)
    private String territory;

    @Column(length = 4)
    private String initials;

    @Column(length = 20)
    private String color = "#0078d4";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    // V13 — account security. mustChangePassword gates every API call except
    // the change-password flow; tokenVersion is embedded in each JWT and bumped
    // on password change/reset, deactivation, role change or "sign out everywhere".
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @JsonIgnore
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount = 0;

    @JsonIgnore
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @JsonIgnore
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── UserDetails ──────────────────────────────────────────
    @Override
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    @JsonIgnore
    public String getUsername() { return email; }

    @Override
    @JsonIgnore
    public boolean isAccountNonExpired() { return true; }

    @Override
    @JsonIgnore
    public boolean isAccountNonLocked() { return true; }

    @Override
    @JsonIgnore
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    @JsonIgnore
    public boolean isEnabled() { return active; }
}
