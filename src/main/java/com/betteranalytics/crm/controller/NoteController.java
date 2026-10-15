package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Note;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.betteranalytics.crm.service.Paging;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private static final Set<String> RELATED_TYPES = Set.of("Lead", "Opportunity", "Contact", "Company", "Meeting");

    private final NoteRepository noteRepository;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final MeetingRepository meetingRepository;

    public NoteController(NoteRepository noteRepository, CurrentUser currentUser,
                           PermissionService permissionService, AuditService auditService,
                           ObjectMapper objectMapper, LeadRepository leadRepository,
                           OpportunityRepository opportunityRepository, ContactRepository contactRepository,
                           CompanyRepository companyRepository, MeetingRepository meetingRepository) {
        this.noteRepository = noteRepository;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.meetingRepository = meetingRepository;
    }

    /** Filtering, sorting and paging happen in the database; {@code count} is the real total. */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String relatedType,
                                     @RequestParam(required = false) Long relatedId,
                                     @RequestParam(required = false) Boolean pinned,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        permissionService.require(actor, Action.VIEW, ResourceType.NOTE, orgId, actor.getId());
        boolean orgWide = permissionService.can(actor, Action.VIEW, ResourceType.NOTE, orgId, Long.MIN_VALUE);
        Specification<Note> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("organizationId"), orgId));
            if (!orgWide) ps.add(cb.equal(root.get("createdBy"), actor.getId()));
            if (relatedType != null && relatedId != null) {
                ps.add(cb.equal(root.get("relatedType"), relatedType));
                ps.add(cb.equal(root.get("relatedId"), relatedId));
            }
            if (pinned != null) ps.add(cb.equal(root.get("pinned"), pinned));
            if (q != null && !q.isBlank()) {
                String like = "%" + ProjectController.escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'),
                             cb.like(cb.lower(root.get("body")), like, '\\')));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Note> result = noteRepository.findAll(spec, Paging.of(page, size, limit, 200, 500,
                Sort.by(Sort.Order.desc("pinned"), Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", result.getTotalElements());
        out.put("data", result.getContent());
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Note> get(@PathVariable Long id) {
        Note note = noteRepository.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
        if (note == null) return ResponseEntity.notFound().build();
        permissionService.require(currentUser.get(), Action.VIEW, ResourceType.NOTE,
                note.getOrganizationId(), note.getCreatedBy());
        return ResponseEntity.ok(note);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.NOTE,
                actor.getOrganizationId(), null);
        Note body = bind(raw);
        if (body.getBody() == null || body.getBody().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "body is required"));
        }
        String relatedType = body.getRelatedType() == null || body.getRelatedType().isBlank() ? null : body.getRelatedType();
        String relatedError = validateRelated(relatedType, body.getRelatedId(), actor.getOrganizationId());
        if (relatedError != null) return ResponseEntity.badRequest().body(Map.of("error", relatedError));

        Note note = new Note();
        note.setOrganizationId(actor.getOrganizationId());
        note.setTitle(body.getTitle());
        note.setBody(body.getBody());
        note.setRelatedType(relatedType);
        note.setRelatedId(relatedType != null ? body.getRelatedId() : null);
        note.setPinned(body.isPinned());
        note.setCreatedBy(actor.getId());
        note.setCreatedAt(Instant.now());
        note.setUpdatedAt(Instant.now());
        noteRepository.save(note);
        auditService.log("create", "notes", note.getId(),
                note.getTitle() != null ? note.getTitle() : "(note)", null, note);
        return ResponseEntity.status(201).body(note);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Note> update(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        Note existing = noteRepository.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(currentUser.get(), Action.EDIT, ResourceType.NOTE,
                existing.getOrganizationId(), existing.getCreatedBy());
        Note body = bind(raw);
        if (raw.containsKey("title")) existing.setTitle(body.getTitle());
        if (body.getBody() != null && !body.getBody().isBlank()) existing.setBody(body.getBody());
        if (raw.containsKey("pinned")) existing.setPinned(body.isPinned());
        existing.setUpdatedAt(Instant.now());
        noteRepository.save(existing);
        auditService.log("update", "notes", id, existing.getTitle(), null, existing);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Note existing = noteRepository.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(currentUser.get(), Action.DELETE, ResourceType.NOTE,
                existing.getOrganizationId(), existing.getCreatedBy());
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(currentUser.userId());
        noteRepository.save(existing);
        auditService.log("delete", "notes", id,
                existing.getTitle() != null ? existing.getTitle() : "(note)", existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
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
            case "Meeting" -> meetingRepository.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            default -> false;
        };
        return exists ? null : "related record not found";
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private Note bind(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, Note.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
