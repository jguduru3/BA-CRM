package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Document;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Documents (Notion-style rich-text pages linked to any CRM record).
 *
 * GET    /api/documents                               — list all documents in org
 * GET    /api/documents?relatedType=X&relatedId=Y    — list documents linked to a record
 * POST   /api/documents                               — create document
 * GET    /api/documents/{id}                          — get document
 * PUT    /api/documents/{id}                          — update document content/title
 * DELETE /api/documents/{id}                          — soft-delete
 * POST   /api/documents/{id}/restore                  — restore
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Set<String> RELATED_TYPES = Set.of("Lead", "Opportunity", "Contact", "Company", "DatabaseRecord");

    private final DocumentRepository documentRepository;
    private final PermissionService  permissionService;
    private final CurrentUser        currentUser;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final DatabaseRecordRepository databaseRecordRepository;

    public DocumentController(DocumentRepository documentRepository,
                              PermissionService permissionService,
                              CurrentUser currentUser,
                              LeadRepository leadRepository,
                              OpportunityRepository opportunityRepository,
                              ContactRepository contactRepository,
                              CompanyRepository companyRepository,
                              DatabaseRecordRepository databaseRecordRepository) {
        this.documentRepository = documentRepository;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.databaseRecordRepository = databaseRecordRepository;
    }

    @GetMapping
    public ResponseEntity<List<Document>> list(@RequestParam(required = false) String relatedType,
                                                @RequestParam(required = false) Long relatedId,
                                                @RequestParam(defaultValue = "500") int limit) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.DOCUMENT, actor.getOrganizationId(), null);
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        List<Document> docs = relatedType != null && relatedId != null
                ? documentRepository.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByUpdatedAtDesc(
                        actor.getOrganizationId(), relatedType, relatedId, page)
                : documentRepository.findByOrganizationIdOrderByUpdatedAtDesc(actor.getOrganizationId(), page);
        return ResponseEntity.ok(docs.stream()
                .filter(d -> permissionService.can(actor, Action.VIEW, ResourceType.DOCUMENT, d.getOrganizationId(), d.getOwnerId()))
                .toList());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Document body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.DOCUMENT, actor.getOrganizationId(), null);
        String relatedType = blankToNull(body.getRelatedType());
        String relatedError = validateRelated(relatedType, body.getRelatedId(), actor.getOrganizationId());
        if (relatedError != null) return ResponseEntity.badRequest().body(Map.of("error", relatedError));
        Document doc = new Document();
        doc.setOrganizationId(actor.getOrganizationId());
        doc.setTitle(body.getTitle() != null ? body.getTitle() : "Untitled");
        doc.setContentJson(body.getContentJson());
        doc.setRelatedType(relatedType);
        doc.setRelatedId(relatedType != null ? body.getRelatedId() : null);
        doc.setOwnerId(actor.getId());
        doc.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        return ResponseEntity.ok(documentRepository.save(doc));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> get(@PathVariable Long id) {
        User actor = currentUser.get();
        Document doc = documentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.DOCUMENT, actor.getOrganizationId(), doc.getOwnerId());
        return ResponseEntity.ok(doc);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Document body) {
        User actor = currentUser.get();
        Document doc = documentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DOCUMENT, actor.getOrganizationId(), doc.getOwnerId());
        if (body.getTitle() != null) doc.setTitle(body.getTitle());
        if (body.getContentJson() != null) doc.setContentJson(body.getContentJson());
        if (body.getRelatedType() != null || body.getRelatedId() != null) {
            String relatedType = body.getRelatedType() != null ? blankToNull(body.getRelatedType()) : doc.getRelatedType();
            Long relatedId = body.getRelatedId() != null ? body.getRelatedId() : doc.getRelatedId();
            String relatedError = validateRelated(relatedType, relatedId, actor.getOrganizationId());
            if (relatedError != null) return ResponseEntity.badRequest().body(Map.of("error", relatedError));
            doc.setRelatedType(relatedType);
            doc.setRelatedId(relatedType != null ? relatedId : null);
        }
        doc.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(documentRepository.save(doc));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        Document doc = documentRepository.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.DOCUMENT, actor.getOrganizationId(), doc.getOwnerId());
        Instant now = Instant.now();
        doc.setDeletedAt(now);
        doc.setDeletedBy(actor.getId());
        documentRepository.save(doc);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    public ResponseEntity<Document> restore(@PathVariable Long id) {
        User actor = currentUser.get();
        Document doc = documentRepository.findByIdAndOrganizationIdIncludingDeleted(id, actor.getOrganizationId()).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.RESTORE, ResourceType.DOCUMENT, actor.getOrganizationId(), doc.getOwnerId());
        doc.setDeletedAt(null);
        doc.setDeletedBy(null);
        doc.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(documentRepository.save(doc));
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
}
