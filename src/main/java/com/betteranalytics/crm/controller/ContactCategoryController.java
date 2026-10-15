package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.ContactCategory;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ContactCategoryRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.Paging;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET    /api/contact-categories           — list (auto-seeds predefined on first call; ?page=&size=)
 * POST   /api/contact-categories           — add custom category
 * PUT    /api/contact-categories/{id}      — partial update (custom only)
 * DELETE /api/contact-categories/{id}      — delete (custom only); contacts using it are un-categorised
 *
 * Access: PermissionService on CONTACT_CATEGORY — everyone can list; creating,
 * updating and deleting is Manager+ (SUPPORT is read-only).
 */
@RestController
@RequestMapping("/api/contact-categories")
public class ContactCategoryController {

    private static final List<String[]> PREDEFINED = List.of(
        new String[]{"Movie & Entertainment", "#FF6B6B", "film"},
        new String[]{"Healthcare / Hospitals", "#4ECDC4", "medical"},
        new String[]{"Hospitality",            "#45B7D1", "hotel"},
        new String[]{"Food & Restaurants",     "#FFA07A", "restaurant"},
        new String[]{"Manufacturing / Industries", "#95A5A6", "factory"},
        new String[]{"Real Estate",            "#27AE60", "home"},
        new String[]{"Finance & Banking",      "#2ECC71", "bank"},
        new String[]{"Education",              "#3498DB", "school"},
        new String[]{"Technology / IT",        "#9B59B6", "code"},
        new String[]{"Retail & E-commerce",    "#E67E22", "cart"},
        new String[]{"Construction",           "#D35400", "construct"},
        new String[]{"Government / Public Sector", "#7F8C8D", "gov"},
        new String[]{"Media & Publishing",     "#1ABC9C", "media"},
        new String[]{"Consulting",             "#2980B9", "briefcase"},
        new String[]{"Non-Profit / NGO",       "#E74C3C", "heart"}
    );

    /** Serialises seeding within this JVM; the (organization_id, name) unique constraint covers other instances. */
    private static final Object SEED_LOCK = new Object();

    private final ContactCategoryRepository repo;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public ContactCategoryController(ContactCategoryRepository repo, CurrentUser currentUser,
                                     ObjectMapper objectMapper, PermissionService permissionService,
                                     AuditService auditService) {
        this.repo = repo;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @GetMapping
    public List<ContactCategory> list(@RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        permissionService.require(actor, Action.VIEW, ResourceType.CONTACT_CATEGORY, orgId, actor.getId());
        if (!repo.existsByOrganizationId(orgId)) seedPredefined(orgId);
        return repo.findByOrganizationIdOrderByIsSystemDescNameAsc(orgId, Paging.of(page, size, 500, 500, null));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.CONTACT_CATEGORY, actor.getOrganizationId(), null);
        ContactCategory body = bind(raw);
        if (body.getName() == null || body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        String name = body.getName().trim();
        if (repo.existsByOrganizationIdAndName(actor.getOrganizationId(), name)) {
            return ResponseEntity.status(409).body(Map.of("error", "a category with this name already exists"));
        }
        ContactCategory c = new ContactCategory();
        c.setOrganizationId(actor.getOrganizationId());
        c.setName(name);
        c.setDescription(body.getDescription());
        c.setColor(body.getColor());
        c.setIcon(body.getIcon());
        c.setIndustry(body.getIndustry());
        c.setCountry(body.getCountry());
        c.setState(body.getState());
        c.setSystem(false);
        c.setActive(true);
        c.setCreatedBy(actor.getId());
        c.setCreatedAt(Instant.now());
        ContactCategory saved = repo.saveAndFlush(c);
        auditService.log("create", "contact_categories", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        ContactCategory existing = repo.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .orElse(null);
        if (existing == null) return ResponseEntity.status(404).body(Map.of("error", "category not found"));
        permissionService.require(actor, Action.EDIT, ResourceType.CONTACT_CATEGORY,
                existing.getOrganizationId(), existing.getCreatedBy());
        if (existing.isSystem()) {
            return ResponseEntity.badRequest().body(Map.of("error", "System categories are read-only"));
        }
        ContactCategory body = bind(raw);
        if (body.getName() != null && !body.getName().isBlank()) {
            String name = body.getName().trim();
            if (repo.existsByOrganizationIdAndNameAndIdNot(actor.getOrganizationId(), name, id)) {
                return ResponseEntity.status(409).body(Map.of("error", "a category with this name already exists"));
            }
            existing.setName(name);
        }
        if (raw.containsKey("description")) existing.setDescription(body.getDescription());
        if (raw.containsKey("color"))       existing.setColor(body.getColor());
        if (raw.containsKey("icon"))        existing.setIcon(body.getIcon());
        if (raw.containsKey("industry"))    existing.setIndustry(body.getIndustry());
        if (raw.containsKey("country"))     existing.setCountry(body.getCountry());
        if (raw.containsKey("state"))       existing.setState(body.getState());
        if (raw.containsKey("active"))      existing.setActive(body.isActive());
        ContactCategory saved = repo.saveAndFlush(existing);
        auditService.log("update", "contact_categories", id, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        ContactCategory existing = repo.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.CONTACT_CATEGORY,
                existing.getOrganizationId(), existing.getCreatedBy());
        if (existing.isSystem()) {
            return ResponseEntity.badRequest().body(Map.of("error", "System categories cannot be deleted"));
        }
        // contacts.category_id has no FK — clear it so no contact points at a deleted category.
        int cleared = repo.clearFromContacts(actor.getOrganizationId(), id);
        repo.delete(existing);
        auditService.log("delete", "contact_categories", id, existing.getName(), existing,
                Map.of("contactsCleared", cleared));
        return ResponseEntity.ok(Map.of("ok", true, "contactsCleared", cleared));
    }

    /**
     * Idempotent, race-safe seeding: serialised in-process, every row is
     * check-then-insert, and a concurrent insert from another instance (caught
     * by the unique (organization_id, name) constraint) is simply skipped.
     */
    private void seedPredefined(Long orgId) {
        synchronized (SEED_LOCK) {
            for (String[] p : PREDEFINED) {
                if (repo.existsByOrganizationIdAndName(orgId, p[0])) continue;
                ContactCategory c = new ContactCategory();
                c.setOrganizationId(orgId);
                c.setName(p[0]);
                c.setColor(p[1]);
                c.setIcon(p[2]);
                c.setSystem(true);
                c.setActive(true);
                c.setCreatedAt(Instant.now());
                try {
                    repo.save(c);
                } catch (DataIntegrityViolationException alreadySeeded) {
                    // another instance seeded this row concurrently — fine
                }
            }
        }
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private ContactCategory bind(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, ContactCategory.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
