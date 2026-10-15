package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Document;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.Paging;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Documents (Notion-style pages linked to any CRM record). The page body is a
 * block list stored by RecordPageController under relatedType "Document";
 * {@code contentJson} is the legacy plain-text body, kept for compatibility.
 *
 * GET    /api/documents                               — list (?q=&relatedType=&relatedId=&page=&size=)
 * POST   /api/documents                               — create document
 * GET    /api/documents/{id}                          — get document
 * PUT    /api/documents/{id}                          — partial update (only the keys sent change)
 * DELETE /api/documents/{id}                          — soft-delete (its page blocks are kept for restore)
 * POST   /api/documents/{id}/restore                  — restore
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Set<String> RELATED_TYPES = Set.of("Lead", "Opportunity", "Contact", "Company", "DatabaseRecord");

    private final DocumentRepository documentRepository;
    private final PermissionService  permissionService;
    private final CurrentUser        currentUser;
    private final AuditService       auditService;
    private final ObjectMapper       objectMapper;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final DatabaseRecordRepository databaseRecordRepository;

    public DocumentController(DocumentRepository documentRepository,
                              PermissionService permissionService,
                              CurrentUser currentUser,
                              AuditService auditService,
                              ObjectMapper objectMapper,
                              LeadRepository leadRepository,
                              OpportunityRepository opportunityRepository,
                              ContactRepository contactRepository,
                              CompanyRepository companyRepository,
                              DatabaseRecordRepository databaseRecordRepository) {
        this.documentRepository = documentRepository;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.databaseRecordRepository = databaseRecordRepository;
    }

    @GetMapping
    public ResponseEntity<List<Document>> list(@RequestParam(required = false) String relatedType,
                                                @RequestParam(required = false) Long relatedId,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(required = false) Integer limit,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        permissionService.require(actor, Action.VIEW, ResourceType.DOCUMENT, orgId, actor.getId());
        boolean orgWide = permissionService.can(actor, Action.VIEW, ResourceType.DOCUMENT, orgId, Long.MIN_VALUE);
        Specification<Document> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("organizationId"), orgId));
            if (!orgWide) ps.add(cb.equal(root.get("ownerId"), actor.getId()));
            if (relatedType != null && relatedId != null) {
                ps.add(cb.equal(root.get("relatedType"), relatedType));
                ps.add(cb.equal(root.get("relatedId"), relatedId));
            }
            if (q != null && !q.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("title")),
                        "%" + ProjectController.escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%", '\\'));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return ResponseEntity.ok(documentRepository.findAll(spec, Paging.of(page, size, limit, 500, 500,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))).getContent());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.DOCUMENT, actor.getOrganizationId(), null);
        Document body = bind(raw);
        String relatedType = blankToNull(body.getRelatedType());
        String relatedError = validateRelated(relatedType, body.getRelatedId(), actor.getOrganizationId());
        if (relatedError != null) return ResponseEntity.badRequest().body(Map.of("error", relatedError));
        Document doc = new Document();
        doc.setOrganizationId(actor.getOrganizationId());
        doc.setTitle(body.getTitle() != null && !body.getTitle().isBlank() ? body.getTitle().trim() : "Untitled");
        doc.setContentJson(body.getContentJson());
        doc.setRelatedType(relatedType);
        doc.setRelatedId(relatedType != null ? body.getRelatedId() : null);
        doc.setOwnerId(actor.getId());
        doc.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        Document saved = documentRepository.save(doc);
        auditService.log("create", "documents", saved.getId(), saved.getTitle(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> get(@PathVariable Long id) {
        User actor = currentUser.get();
        Document doc = documentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.DOCUMENT, doc.getOrganizationId(), doc.getOwnerId());
        return ResponseEntity.ok(doc);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        Document doc = documentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DOCUMENT, doc.getOrganizationId(), doc.getOwnerId());
        Document body = bind(raw);
        if (body.getTitle() != null && !body.getTitle().isBlank()) doc.setTitle(body.getTitle().trim());
        if (raw.containsKey("contentJson")) doc.setContentJson(body.getContentJson());
        if (raw.containsKey("relatedType") || raw.containsKey("relatedId")) {
            String relatedType = raw.containsKey("relatedType") ? blankToNull(body.getRelatedType()) : doc.getRelatedType();
            Long relatedId = raw.containsKey("relatedId") ? body.getRelatedId() : doc.getRelatedId();
            String relatedError = validateRelated(relatedType, relatedId, actor.getOrganizationId());
            if (relatedError != null) return ResponseEntity.badRequest().body(Map.of("error", relatedError));
            doc.setRelatedType(relatedType);
            doc.setRelatedId(relatedType != null ? relatedId : null);
        }
        doc.setUpdatedAt(Instant.now());
        Document saved = documentRepository.save(doc);
        auditService.log("update", "documents", id, saved.getTitle(), null, saved);
        return ResponseEntity.ok(saved);
    }

    /** Soft delete. The document's page blocks stay attached so a restore brings the page back intact. */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        Document doc = documentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.DOCUMENT, doc.getOrganizationId(), doc.getOwnerId());
        Instant now = Instant.now();
        doc.setDeletedAt(now);
        doc.setDeletedBy(actor.getId());
        documentRepository.save(doc);
        auditService.log("delete", "documents", id, doc.getTitle(), doc, null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @Transactional
    public ResponseEntity<Document> restore(@PathVariable Long id) {
        User actor = currentUser.get();
        Document doc = documentRepository.findByIdAndOrganizationIdIncludingDeleted(id, actor.getOrganizationId()).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.RESTORE, ResourceType.DOCUMENT, doc.getOrganizationId(), doc.getOwnerId());
        doc.setDeletedAt(null);
        doc.setDeletedBy(null);
        doc.setUpdatedAt(Instant.now());
        Document saved = documentRepository.save(doc);
        auditService.log("restore", "documents", id, saved.getTitle(), null, saved);
        return ResponseEntity.ok(saved);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns an error message when relatedType/relatedId don't point at a record in the caller's org. */
    private String validateRelated(String relatedType, Long relatedId, Long orgId) {
        if (relatedType == null) return null;
        if (!RELATED_TYPES.contains(relatedType)) return "unsupported relatedType";
        if (relatedId == null) return "relatedId is required with relatedType";
        boolean exists = switch (relatedType) {
            case "Lead" -> leadRepository.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "Opportunity" -> opportunityRepository.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "Contact" -> contactRepository.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "Company" -> companyRepository.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "DatabaseRecord" -> databaseRecordRepository.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            default -> false;
        };
        return exists ? null : "related record not found";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private Document bind(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, Document.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
