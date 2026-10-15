package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Company;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.CompanyRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
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
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyRepository companyRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final TenantGuard tenantGuard;

    public CompanyController(CompanyRepository companyRepository, CurrentUser currentUser,
                              AuditService auditService, PermissionService permissionService,
                              TenantGuard tenantGuard) {
        this.companyRepository = companyRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.permissionService = permissionService;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String search,
                                     @RequestParam(defaultValue = "100") int limit) {
        User actor = currentUser.get();
        String q = search.toLowerCase();
        List<Company> filtered = companyRepository.findByOrganizationId(currentUser.organizationId()).stream()
                .filter(c -> q.isBlank() || (c.getName() != null && c.getName().toLowerCase().contains(q)))
                .filter(c -> permissionService.can(actor, Action.VIEW, ResourceType.COMPANY, c.getOrganizationId(), c.getOwnerId()))
                .collect(Collectors.toList());
        return Map.of("count", filtered.size(),
                      "data", filtered.stream().limit(TenantGuard.clampLimit(limit)).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Company> get(@PathVariable Long id) {
        Company company = find(id).orElse(null);
        if (company == null) throw new NoSuchElementException("company not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.COMPANY, company.getOrganizationId(), company.getOwnerId());
        return ResponseEntity.ok(company);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Company body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.COMPANY, currentUser.organizationId(), null);
        if (body.getName() == null || body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (body.getOwnerId() != null && !body.getOwnerId().equals(actor.getId())) {
            requireAssignableOwner(actor, body.getOwnerId());
        }
        // H12: the entity is bound straight from JSON — reset every server-owned column
        body.setId(null);
        body.setOrganizationId(currentUser.organizationId());
        body.setDeletedAt(null);
        body.setDeletedBy(null);
        body.setDeleteReason(null);
        if (body.getOwnerId() == null) body.setOwnerId(currentUser.userId());
        if (body.getStatus() == null) body.setStatus("Prospect"); // column is NOT NULL
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        companyRepository.save(body);
        auditService.log("create", "companies", body.getId(), body.getName(), null, body);
        return ResponseEntity.status(201).body(body);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Company body) {
        Company existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("company not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.COMPANY, existing.getOrganizationId(), existing.getOwnerId());
        if (body.getName() != null && body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name cannot be blank"));
        }
        // H10: Company EDIT is org-wide for every sales role, so without this any rep
        // could take ownership of (or dump) any account.
        if (body.getOwnerId() != null && !body.getOwnerId().equals(existing.getOwnerId())) {
            requireAssignableOwner(actor, body.getOwnerId());
        }

        Company before = copyOf(existing);
        if (body.getName() != null) existing.setName(body.getName());
        if (body.getIndustry() != null) existing.setIndustry(body.getIndustry());
        if (body.getSize() != null) existing.setSize(body.getSize());
        if (body.getWebsite() != null) existing.setWebsite(body.getWebsite());
        if (body.getPhone() != null) existing.setPhone(body.getPhone());
        if (body.getEmail() != null) existing.setEmail(body.getEmail());
        if (body.getLocation() != null) existing.setLocation(body.getLocation());
        if (body.getRevenue() != null) existing.setRevenue(body.getRevenue());
        if (body.getStatus() != null) existing.setStatus(body.getStatus());
        if (body.getNotes() != null) existing.setNotes(body.getNotes());
        if (body.getOwnerId() != null) existing.setOwnerId(body.getOwnerId());
        existing.setUpdatedAt(Instant.now());
        companyRepository.save(existing);
        auditService.log("update", "companies", id, existing.getName(), before, existing);
        return ResponseEntity.ok(existing);
    }

    // Soft delete — sets deleted_at/deleted_by/delete_reason instead of
    // destroying the row. The entity's @SQLRestriction then hides it from
    // every normal query automatically.
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Company existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("company not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.COMPANY, existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(currentUser.userId());
        existing.setDeleteReason(reason);
        companyRepository.save(existing);
        auditService.log("delete", "companies", id, existing.getName(), existing, Map.of("reason", reason == null ? "" : reason));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id) {
        Company existing = companyRepository.findByIdAndOrganizationIdIncludingDeleted(id, currentUser.organizationId()).orElse(null);
        if (existing == null) throw new NoSuchElementException("company not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.COMPANY, existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(null);
        existing.setDeletedBy(null);
        existing.setDeleteReason(null);
        companyRepository.save(existing);
        auditService.log("update", "companies", id, existing.getName(), null, Map.of("restored", true));
        return ResponseEntity.ok(existing);
    }

    @GetMapping("/trash")
    public List<Company> trash() {
        User actor = currentUser.get();
        return companyRepository.findTrashedByOrganizationId(currentUser.organizationId()).stream()
                .filter(c -> permissionService.can(actor, Action.DELETE, ResourceType.COMPANY, c.getOrganizationId(), c.getOwnerId()))
                .collect(Collectors.toList());
    }

    private Optional<Company> find(Long id) {
        return companyRepository.findByIdAndOrganizationId(id, currentUser.organizationId());
    }

    /**
     * Ownership changes follow the same rule as Lead.ownerId: Manager+ only,
     * Managers only within their team, and the target must be in this org (H10/M1).
     */
    private void requireAssignableOwner(User actor, Long ownerId) {
        tenantGuard.requireUser(ownerId);
        if (!permissionService.canEditLeadOwner(actor) || !permissionService.canAssignTo(actor, ownerId)) {
            throw new AccessDeniedException("you are not permitted to assign this company to that user");
        }
    }

    private Company copyOf(Company c) {
        Company copy = new Company();
        copy.setId(c.getId());
        copy.setOrganizationId(c.getOrganizationId());
        copy.setName(c.getName());
        copy.setIndustry(c.getIndustry());
        copy.setSize(c.getSize());
        copy.setWebsite(c.getWebsite());
        copy.setPhone(c.getPhone());
        copy.setEmail(c.getEmail());
        copy.setLocation(c.getLocation());
        copy.setRevenue(c.getRevenue());
        copy.setStatus(c.getStatus());
        copy.setNotes(c.getNotes());
        copy.setOwnerId(c.getOwnerId());
        copy.setCreatedAt(c.getCreatedAt());
        copy.setUpdatedAt(c.getUpdatedAt());
        return copy;
    }
}
