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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String relatedType,
                                     @RequestParam(required = false) Long relatedId,
                                     @RequestParam(defaultValue = "200") int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        List<Note> all;
        if (relatedType != null && relatedId != null) {
            all = noteRepository.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByPinnedDescCreatedAtDesc(
                    currentUser.organizationId(), relatedType, relatedId, page);
        } else {
            all = noteRepository.findByOrganizationIdOrderByCreatedAtDesc(currentUser.organizationId(), page);
        }
        User actor = currentUser.get();
        List<Note> filtered = all.stream()
                .filter(n -> permissionService.can(actor, Action.VIEW, ResourceType.NOTE,
                        n.getOrganizationId(), n.getCreatedBy()))
                .collect(Collectors.toList());
        return Map.of("count", filtered.size(), "data", filtered);
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
    public ResponseEntity<Note> update(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        Note existing = noteRepository.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(currentUser.get(), Action.EDIT, ResourceType.NOTE,
                existing.getOrganizationId(), existing.getCreatedBy());
        Note body = bind(raw);
        if (body.getTitle() != null) existing.setTitle(body.getTitle());
        if (body.getBody() != null && !body.getBody().isBlank()) existing.setBody(body.getBody());
        if (raw.containsKey("pinned")) existing.setPinned(body.isPinned());
        existing.setUpdatedAt(Instant.now());
        noteRepository.save(existing);
        auditService.log("update", "notes", id, existing.getTitle(), null, existing);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
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
