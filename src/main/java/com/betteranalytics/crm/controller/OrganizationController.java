package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Organization;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.OrganizationRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Map;

/**
 * Organization profile — read by all users, written only by SUPER_ADMIN.
 * Exposes the current tenant's name, slug, plan, and industry (plan is read-only here).
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final OrganizationRepository organizationRepository;
    private final CurrentUser            currentUser;
    private final AuditService           auditService;
    private final PermissionService      permissionService;

    public OrganizationController(OrganizationRepository organizationRepository,
                                   CurrentUser currentUser,
                                   AuditService auditService,
                                   PermissionService permissionService) {
        this.organizationRepository = organizationRepository;
        this.currentUser            = currentUser;
        this.auditService           = auditService;
        this.permissionService      = permissionService;
    }

    /** Returns the current user's organization profile. */
    @GetMapping("/me")
    public ResponseEntity<Organization> me() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.ORG_SETTINGS, actor.getOrganizationId(), null);
        Organization org = organizationRepository.findById(currentUser.organizationId()).orElse(null);
        if (org == null) throw new NoSuchElementException("organization not found");
        return ResponseEntity.ok(org);
    }

    /** Updates org name or industry. SUPER_ADMIN only (plan is not tenant-editable). */
    @PutMapping("/me")
    public ResponseEntity<?> update(@RequestBody Organization body) {
        User actor = currentUser.get();
        // ORG_SETTINGS EDIT: Super Admin only (FeaturePermissionTable)
        permissionService.require(actor, Action.EDIT, ResourceType.ORG_SETTINGS, actor.getOrganizationId(), null);
        Organization org = organizationRepository.findById(currentUser.organizationId()).orElse(null);
        if (org == null) throw new NoSuchElementException("organization not found");

        Organization before = copyOf(org);
        if (body.getName() != null && body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name cannot be blank"));
        }
        if (body.getName()     != null) org.setName(body.getName().trim());
        if (body.getIndustry() != null) org.setIndustry(body.getIndustry());
        // H15: plan is billing state, not tenant profile — a tenant's own Super
        // Admin must not be able to upgrade themselves. Plan changes belong to a
        // platform/billing flow, so any "plan" in the body is ignored here.
        org.setUpdatedAt(Instant.now());
        organizationRepository.save(org);
        auditService.log("update", "organizations", org.getId(), org.getName(), before, org);
        return ResponseEntity.ok(org);
    }

    private Organization copyOf(Organization o) {
        Organization c = new Organization();
        c.setId(o.getId()); c.setName(o.getName()); c.setSlug(o.getSlug());
        c.setPlan(o.getPlan()); c.setIndustry(o.getIndustry()); c.setActive(o.isActive());
        c.setCreatedAt(o.getCreatedAt()); c.setUpdatedAt(o.getUpdatedAt());
        return c;
    }
}
