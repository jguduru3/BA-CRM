package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.ListParams;
import com.betteranalytics.crm.core.Patch;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.dedupe.DuplicateGuard;
import com.betteranalytics.crm.dedupe.ObjectType;
import com.betteranalytics.crm.dedupe.Probe;
import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ContactRepository;
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
@RequestMapping("/api/contacts")
public class ContactController {

    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name", "email", "email", "title", "title", "status", "status",
            "lastContactedAt", "lastContactedAt", "createdAt", "createdAt", "updatedAt", "updatedAt", "id", "id");

    private final ContactRepository contactRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final TenantGuard tenantGuard;
    private final DuplicateGuard duplicateGuard;

    public ContactController(ContactRepository contactRepository, CurrentUser currentUser,
                             AuditService auditService, PermissionService permissionService,
                             TenantGuard tenantGuard, DuplicateGuard duplicateGuard) {
        this.contactRepository = contactRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.permissionService = permissionService;
        this.tenantGuard = tenantGuard;
        this.duplicateGuard = duplicateGuard;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "") String search,
                                    @RequestParam(required = false) Long companyId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long categoryId,
                                    @RequestParam(required = false) Long ownerId,
                                    @RequestParam(required = false) String industry,
                                    @RequestParam(required = false) String country,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String sort) {
        User actor = currentUser.get();
        Specification<Contact> spec = visibleSpec(actor)
                .and(Specs.search(search, "name", "email", "phone", "title"))
                .and(Specs.eq("companyId", companyId))
                .and(Specs.eqIgnoreCase("status", status))
                .and(Specs.eq("categoryId", categoryId))
                .and(Specs.eq("ownerId", ownerId))
                .and(Specs.eqIgnoreCase("industry", industry))
                .and(Specs.eqIgnoreCase("country", country));
        Page<Contact> result = contactRepository.findAll(spec,
                ListParams.pageable(page, size, limit, sort, SORTABLE, Sort.by(Sort.Direction.ASC, "name")));
        return ListParams.response(result, result.getContent());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Contact> get(@PathVariable Long id) {
        Contact contact = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CONTACT, contact.getOrganizationId(), contact.getOwnerId());
        return ResponseEntity.ok(contact);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody JsonNode body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.CONTACT, currentUser.organizationId(), null);
        Patch p = Patch.of(body);
        if (!p.hasValue("name")) throw new IllegalArgumentException("name is required");
        Long ownerId = p.lng("ownerId");
        if (ownerId != null && !ownerId.equals(actor.getId())) requireAssignableOwner(actor, ownerId);
        // M1: linked company / category must belong to this tenant
        tenantGuard.requireCompany(p.lng("companyId"));
        tenantGuard.requireContactCategory(p.lng("categoryId"));

        Contact contact = new Contact();
        apply(contact, p);
        contact.setOrganizationId(currentUser.organizationId());
        DuplicateGuard.Outcome duplicates = duplicateGuard.checkCreate(actor, ObjectType.CONTACT, Probe.of(contact));
        contact.setOwnerId(ownerId != null ? ownerId : actor.getId());
        contact.setCreatedAt(Instant.now());
        contact.setUpdatedAt(Instant.now());
        contactRepository.save(contact);
        auditService.log("create", "contacts", contact.getId(), contact.getName(), null, contact);
        duplicateGuard.afterSave(duplicates, contact.getId(), contact.getName());
        return ResponseEntity.status(201).body(contact);
    }

    /** Partial update (PUT and PATCH): only fields present in the body change. */
    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody JsonNode body) {
        Contact existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CONTACT, existing.getOrganizationId(), existing.getOwnerId());
        Patch p = Patch.of(body);
        if (p.has("name") && !p.hasValue("name")) throw new IllegalArgumentException("name cannot be blank");
        // H10: ownership changes are Manager+ and team-bounded.
        Long newOwner = p.lng("ownerId");
        if (newOwner != null && !newOwner.equals(existing.getOwnerId())) requireAssignableOwner(actor, newOwner);
        if (p.has("companyId") && !Objects.equals(p.lng("companyId"), existing.getCompanyId())) {
            tenantGuard.requireCompany(p.lng("companyId"));
        }
        if (p.has("categoryId") && !Objects.equals(p.lng("categoryId"), existing.getCategoryId())) {
            tenantGuard.requireContactCategory(p.lng("categoryId"));
        }

        Contact before = snapshot(existing);
        apply(existing, p);
        if (newOwner != null) existing.setOwnerId(newOwner);
        DuplicateGuard.Outcome duplicates =
                duplicateGuard.checkEdit(actor, ObjectType.CONTACT, Probe.of(before), Probe.of(existing));
        existing.setUpdatedAt(Instant.now());
        contactRepository.save(existing);
        auditService.log("update", "contacts", id, existing.getName(), before, existing);
        duplicateGuard.afterSave(duplicates, id, existing.getName());
        return ResponseEntity.ok(existing);
    }

    // Soft delete — see CompanyController for the full rationale.
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Contact existing = find(id);
        User actor = currentUser.get();
        permissionService.require(actor, Action.DELETE, ResourceType.CONTACT, existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(currentUser.userId());
        existing.setDeleteReason(reason);
        contactRepository.save(existing);
        auditService.log("delete", "contacts", id, existing.getName(), existing, Map.of("reason", reason == null ? "" : reason));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Long id) {
        Contact existing = contactRepository.findByIdAndOrganizationIdIncludingDeleted(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("contact not found"));
        User actor = currentUser.get();
        permissionService.require(actor, Action.RESTORE, ResourceType.CONTACT, existing.getOrganizationId(), existing.getOwnerId());
        existing.setDeletedAt(null);
        existing.setDeletedBy(null);
        existing.setDeleteReason(null);
        contactRepository.save(existing);
        auditService.log("update", "contacts", id, existing.getName(), null, Map.of("restored", true));
        return ResponseEntity.ok(existing);
    }

    @GetMapping("/trash")
    public List<Contact> trash() {
        User actor = currentUser.get();
        return contactRepository.findTrashedByOrganizationId(currentUser.organizationId()).stream()
                .filter(c -> permissionService.can(actor, Action.DELETE, ResourceType.CONTACT, c.getOrganizationId(), c.getOwnerId()))
                .collect(Collectors.toList());
    }

    private Specification<Contact> visibleSpec(User actor) {
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.CONTACT);
        if (scope.none()) throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW contacts");
        return Specs.<Contact>org(actor.getOrganizationId()).and(Specs.ownedWithin(scope, "ownerId"));
    }

    private Contact find(Long id) {
        return contactRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElseThrow(() -> new NoSuchElementException("contact not found"));
    }

    /** Same rule as Lead.ownerId: Manager+ only, Managers within their team, target in this org. */
    private void requireAssignableOwner(User actor, Long ownerId) {
        tenantGuard.requireUser(ownerId);
        if (!permissionService.canEditLeadOwner(actor) || !permissionService.canAssignTo(actor, ownerId)) {
            throw new AccessDeniedException("you are not permitted to assign this contact to that user");
        }
    }

    /** Client-editable fields; linkedin must be a safe http(s) link (400 otherwise). */
    private void apply(Contact c, Patch p) {
        p.requiredText("name", c::setName);
        p.text("title", c::setTitle);
        p.text("email", c::setEmail);
        p.text("phone", c::setPhone);
        p.longValue("companyId", c::setCompanyId);
        p.requiredText("status", c::setStatus);
        p.text("source", c::setSource);
        p.text("notes", c::setNotes);
        p.text("tags", c::setTags);
        if (p.has("linkedin")) c.setLinkedin(UrlFields.normalize("linkedin", p.str("linkedin")));
        p.instantValue("lastContactedAt", c::setLastContactedAt);
        p.longValue("categoryId", c::setCategoryId);
        p.text("industry", c::setIndustry);
        p.text("country", c::setCountry);
        p.text("state", c::setState);
        p.text("natureOfBusiness", c::setNatureOfBusiness);
    }

    private static Contact snapshot(Contact c) {
        Contact copy = new Contact();
        BeanUtils.copyProperties(c, copy);
        return copy;
    }
}
