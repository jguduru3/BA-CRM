package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ContactRepository;
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
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactRepository contactRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;
    private final PermissionService permissionService;
    private final TenantGuard tenantGuard;

    public ContactController(ContactRepository contactRepository, CurrentUser currentUser,
                              AuditService auditService, PermissionService permissionService,
                              TenantGuard tenantGuard) {
        this.contactRepository = contactRepository;
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
        List<Contact> filtered = contactRepository.findByOrganizationId(currentUser.organizationId()).stream()
                .filter(c -> q.isBlank() || (c.getName() != null && c.getName().toLowerCase().contains(q)))
                .filter(c -> permissionService.can(actor, Action.VIEW, ResourceType.CONTACT, c.getOrganizationId(), c.getOwnerId()))
                .collect(Collectors.toList());
        return Map.of("count", filtered.size(),
                      "data", filtered.stream().limit(TenantGuard.clampLimit(limit)).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Contact> get(@PathVariable Long id) {
        Contact contact = find(id).orElse(null);
        if (contact == null) throw new NoSuchElementException("contact not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.CONTACT,
                                  contact.getOrganizationId(), contact.getOwnerId());
        return ResponseEntity.ok(contact);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Contact body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.CONTACT, currentUser.organizationId(), null);
        if (body.getName() == null || body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (body.getOwnerId() != null && !body.getOwnerId().equals(actor.getId())) {
            requireAssignableOwner(actor, body.getOwnerId());
        }
        // M1: linked company / category must belong to this tenant
        tenantGuard.requireCompany(body.getCompanyId());
        tenantGuard.requireContactCategory(body.getCategoryId());

        // H12: the entity is bound straight from JSON — reset every server-owned column
        body.setId(null);
        body.setOrganizationId(currentUser.organizationId());
        body.setDeletedAt(null);
        body.setDeletedBy(null);
        body.setDeleteReason(null);
        if (body.getOwnerId() == null) body.setOwnerId(currentUser.userId());
        if (body.getStatus() == null) body.setStatus("Active"); // column is NOT NULL
        body.setCreatedAt(Instant.now());
        body.setUpdatedAt(Instant.now());
        contactRepository.save(body);
        auditService.log("create", "contacts", body.getId(), body.getName(), null, body);
        return ResponseEntity.status(201).body(body);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Contact body) {
        Contact existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("contact not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.EDIT, ResourceType.CONTACT, existing.getOrganizationId(), existing.getOwnerId());
        if (body.getName() != null && body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name cannot be blank"));
        }
        // H10: an OWN-scope rep could otherwise hand the contact to anyone (or any
        // tenant's user id) — ownership changes are Manager+ and team-bounded.
        if (body.getOwnerId() != null && !body.getOwnerId().equals(existing.getOwnerId())) {
            requireAssignableOwner(actor, body.getOwnerId());
        }
        if (body.getCompanyId() != null && !body.getCompanyId().equals(existing.getCompanyId())) {
            tenantGuard.requireCompany(body.getCompanyId());
        }
        if (body.getCategoryId() != null && !body.getCategoryId().equals(existing.getCategoryId())) {
            tenantGuard.requireContactCategory(body.getCategoryId());
        }

        Contact before = copyOf(existing);
        if (body.getName() != null) existing.setName(body.getName());
        if (body.getTitle() != null) existing.setTitle(body.getTitle());
        if (body.getEmail() != null) existing.setEmail(body.getEmail());
        if (body.getPhone() != null) existing.setPhone(body.getPhone());
        if (body.getCompanyId() != null) existing.setCompanyId(body.getCompanyId());
        if (body.getOwnerId() != null) existing.setOwnerId(body.getOwnerId());
        if (body.getStatus() != null) existing.setStatus(body.getStatus());
        if (body.getSource() != null) existing.setSource(body.getSource());
        if (body.getNotes() != null) existing.setNotes(body.getNotes());
        if (body.getTags() != null) existing.setTags(body.getTags());
        if (body.getLinkedin() != null) existing.setLinkedin(body.getLinkedin());
        if (body.getLastContactedAt() != null) existing.setLastContactedAt(body.getLastContactedAt());
        if (body.getCategoryId() != null) existing.setCategoryId(body.getCategoryId());
        if (body.getIndustry() != null) existing.setIndustry(body.getIndustry());
        if (body.getCountry() != null) existing.setCountry(body.getCountry());
        if (body.getState() != null) existing.setState(body.getState());
        if (body.getNatureOfBusiness() != null) existing.setNatureOfBusiness(body.getNatureOfBusiness());
        existing.setUpdatedAt(Instant.now());
        contactRepository.save(existing);
        auditService.log("update", "contacts", id, existing.getName(), before, existing);
        return ResponseEntity.ok(existing);
    }

    // Soft delete — see CompanyController for the full rationale.
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Contact existing = find(id).orElse(null);
        if (existing == null) throw new NoSuchElementException("contact not found");
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
        Contact existing = contactRepository.findByIdAndOrganizationIdIncludingDeleted(id, currentUser.organizationId()).orElse(null);
        if (existing == null) throw new NoSuchElementException("contact not found");
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

    private Optional<Contact> find(Long id) {
        return contactRepository.findByIdAndOrganizationId(id, currentUser.organizationId());
    }

    /** Same rule as Lead.ownerId: Manager+ only, Managers within their team, target in this org. */
    private void requireAssignableOwner(User actor, Long ownerId) {
        tenantGuard.requireUser(ownerId);
        if (!permissionService.canEditLeadOwner(actor) || !permissionService.canAssignTo(actor, ownerId)) {
            throw new AccessDeniedException("you are not permitted to assign this contact to that user");
        }
    }

    // M5: snapshot every mutable field, otherwise the audit "before" silently
    // omits changes to category/industry/country/state/nature of business.
    private Contact copyOf(Contact c) {
        Contact copy = new Contact();
        copy.setId(c.getId());
        copy.setOrganizationId(c.getOrganizationId());
        copy.setName(c.getName());
        copy.setTitle(c.getTitle());
        copy.setEmail(c.getEmail());
        copy.setPhone(c.getPhone());
        copy.setCompanyId(c.getCompanyId());
        copy.setOwnerId(c.getOwnerId());
        copy.setStatus(c.getStatus());
        copy.setSource(c.getSource());
        copy.setNotes(c.getNotes());
        copy.setTags(c.getTags());
        copy.setLinkedin(c.getLinkedin());
        copy.setLastContactedAt(c.getLastContactedAt());
        copy.setCategoryId(c.getCategoryId());
        copy.setIndustry(c.getIndustry());
        copy.setCountry(c.getCountry());
        copy.setState(c.getState());
        copy.setNatureOfBusiness(c.getNatureOfBusiness());
        copy.setDeletedAt(c.getDeletedAt());
        copy.setDeletedBy(c.getDeletedBy());
        copy.setDeleteReason(c.getDeleteReason());
        copy.setCreatedAt(c.getCreatedAt());
        copy.setUpdatedAt(c.getUpdatedAt());
        return copy;
    }
}
