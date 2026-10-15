package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.CompanyDeletionService;
import com.betteranalytics.crm.core.ListParams;
import com.betteranalytics.crm.core.Patch;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.entity.Company;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.CompanyRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.security.UrlFields;
import com.betteranalytics.crm.service.AuditService;
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
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name", "industry", "industry", "status", "status", "revenue", "revenue",
            "location", "location", "createdAt", "createdAt", "updatedAt", "updatedAt", "id", "id");

    private final CompanyRepository companyRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final TenantGuard tenantGuard;
    private final CompanyDeletionService deletion;

    public CompanyController(CompanyRepository companyRepository, CurrentUser currentUser,
                             AuditService auditService, PermissionService permissionService,
                             TenantGuard tenantGuard, CompanyDeletionService deletion) {
        this.companyRepository = companyRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.permissionService = permissionService;
        this.tenantGuard = tenantGuard;
        this.deletion = deletion;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String search,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String industry,
                                    @RequestParam(required = false) Long ownerId,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String sort) {
        User actor = currentUser.get();
        Specification<Company> spec = visibleSpec(actor)
                .and(Specs.search(search, "name", "industry", "location", "email", "website"))
                .and(Specs.eqIgnoreCase("status", status))
                .and(Specs.eqIgnoreCase("industry", industry))
                .and(Specs.eq("ownerId", ownerId));
        Page<Company> result = companyRepository.findAll(spec,
                ListParams.pageable(page, size, limit, sort, SORTABLE, Sort.by(Sort.Direction.ASC, "name")));
        return ListParams.response(result, result.getContent());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Company> get(@PathVariable Long id) {
        Company company = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.COMPANY, company.getOrganizationId(), company.getOwnerId());
        return ResponseEntity.ok(company);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody JsonNode body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.COMPANY, currentUser.organizationId(), null);
        Patch p = Patch.of(body);
        if (!p.hasValue("name")) throw new IllegalArgumentException("name is required");
        Long ownerId = p.lng("ownerId");
        if (ownerId != null && !ownerId.equals(actor.getId())) requireAssignableOwner(actor, ownerId);

        Company company = new Company();
        apply(company, p);
        company.setOrganizationId(currentUser.organizationId());
        company.setOwnerId(ownerId != null ? ownerId : actor.getId());
        company.setCreatedAt(Instant.now());
        company.setUpdatedAt(Instant.now());
        companyRepository.save(company);
        auditService.log("create", "companies", company.getId(), company.getName(), null, company);
        return ResponseEntity.status(201).body(company);
    }

    /** Partial update (PUT and PATCH): only fields present in the body change. */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody JsonNode body) {
        Company existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.COMPANY, existing.getOrganizationId(), existing.getOwnerId());
        Patch p = Patch.of(body);
        if (p.has("name") && !p.hasValue("name")) throw new IllegalArgumentException("name cannot be blank");
        // H10: Company EDIT is org-wide for every sales role, so ownership changes are
        // Manager+ and team-bounded — otherwise any rep could take over any account.
        Long newOwner = p.lng("ownerId");
        if (newOwner != null && !newOwner.equals(existing.getOwnerId())) requireAssignableOwner(actor, newOwner);

        Company before = snapshot(existing);
        apply(existing, p);
        if (newOwner != null) existing.setOwnerId(newOwner);
        existing.setUpdatedAt(Instant.now());
        companyRepository.save(existing);
        auditService.log("update", "companies", id, existing.getName(), before, existing);
        return ResponseEntity.ok(existing);
    }

    /**
     * Soft delete. A company that still has live contacts / opportunities / leads
     * is refused with 409 and the counts, unless {@code cascade=true} (Manager+),
     * which moves those children to the trash too.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id,
                                    @RequestParam(required = false) String reason,
                                    @RequestParam(defaultValue = "false") boolean cascade) {
        Company existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.COMPANY, existing.getOrganizationId(), existing.getOwnerId());
        Map<String, Long> children = deletion.childCounts(existing);
        boolean hasChildren = children.values().stream().anyMatch(n -> n > 0);
        if (hasChildren && !cascade) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "\"" + existing.getName() + "\" still has " + CompanyDeletionService.describe(children)
                    + ". Move or delete them first, or delete everything together.");
            body.put("code", "HAS_CHILDREN");
            body.put("children", children);
            return ResponseEntity.status(409).body(body);
        }
        if (hasChildren && !isManagerUp(actor)) {
            throw new AccessDeniedException("only a Manager or above can delete a company together with its records");
        }
        Company before = snapshot(existing);
        Map<String, Integer> removed = deletion.softDelete(existing, actor.getId(), reason, cascade && hasChildren);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reason", reason == null ? "" : reason);
        if (!removed.isEmpty()) after.put("cascaded", removed);
        auditService.log("delete", "companies", id, existing.getName(), before, after);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if (!removed.isEmpty()) out.put("cascaded", removed);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id) {
        Company existing = companyRepository.findByIdAndOrganizationIdIncludingDeleted(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("company not found"));
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.COMPANY, existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(null);
        existing.setDeletedBy(null);
        existing.setDeleteReason(null);
        companyRepository.save(existing);
        Map<String, Integer> restored = deletion.restoreCascaded(existing);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("restored", true);
        after.put("children", restored);
        auditService.log("update", "companies", id, existing.getName(), null, after);
        return ResponseEntity.ok(existing);
    }

    @GetMapping("/trash")
    public List<Company> trash() {
        User actor = currentUser.get();
        return companyRepository.findTrashedByOrganizationId(currentUser.organizationId()).stream()
                .filter(c -> permissionService.can(actor, Action.DELETE, ResourceType.COMPANY, c.getOrganizationId(), c.getOwnerId()))
                .collect(Collectors.toList());
    }

    private Specification<Company> visibleSpec(User actor) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.COMPANY);
        if (scope.none()) throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW companies");
        return Specs.<Company>org(actor.getOrganizationId()).and(Specs.ownedWithin(scope, "ownerId"));
    }

    private Company find(Long id) {
        return companyRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("company not found"));
    }

    private static boolean isManagerUp(User u) {
        return u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES || u.getRole() == Role.MANAGER;
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

    /** Client-editable fields; website must be a safe http(s)/mailto link (400 otherwise). */
    private void apply(Company c, Patch p) {
        p.requiredText("name", c::setName);
        p.text("industry", c::setIndustry);
        p.text("size", c::setSize);
        if (p.has("website")) c.setWebsite(UrlFields.normalize("website", p.str("website")));
        p.text("phone", c::setPhone);
        p.text("email", c::setEmail);
        p.text("location", c::setLocation);
        p.longValue("revenue", c::setRevenue);
        p.requiredText("status", c::setStatus);
        p.text("notes", c::setNotes);
        if (c.getRevenue() != null && c.getRevenue() < 0) throw new IllegalArgumentException("revenue must not be negative");
    }

    private static Company snapshot(Company c) {
        Company copy = new Company();
        BeanUtils.copyProperties(c, copy);
        return copy;
    }
}
