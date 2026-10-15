package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Meeting;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private static final Set<String> RELATED_TYPES = Set.of("Lead", "Opportunity", "Contact", "Company");
    private static final Set<String> STATUSES = Set.of("scheduled", "completed", "cancelled", "no_show", "rescheduled");

    private final MeetingRepository meetingRepository;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;

    public MeetingController(MeetingRepository meetingRepository, CurrentUser currentUser,
                              PermissionService permissionService, AuditService auditService,
                              NotificationService notificationService, ObjectMapper objectMapper,
                              UserRepository userRepository, LeadRepository leadRepository,
                              OpportunityRepository opportunityRepository, ContactRepository contactRepository,
                              CompanyRepository companyRepository) {
        this.meetingRepository = meetingRepository;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String relatedType,
                                     @RequestParam(required = false) Long relatedId,
                                     @RequestParam(defaultValue = "100") int limit) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        // ORG-scope viewers page straight from the DB; everyone else is limited to
        // meetings they organize, so the DB-side LIMIT still yields a full page.
        boolean orgWide = permissionService.can(actor, Action.VIEW, ResourceType.MEETING, orgId, Long.MIN_VALUE);
        List<Meeting> all;
        if (relatedType != null && relatedId != null) {
            all = orgWide
                    ? meetingRepository.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByStartAtDesc(orgId, relatedType, relatedId, page)
                    : meetingRepository.findByOrganizationIdAndRelatedTypeAndRelatedIdAndOrganizerIdOrderByStartAtDesc(orgId, relatedType, relatedId, actor.getId(), page);
        } else {
            all = orgWide
                    ? meetingRepository.findByOrganizationIdOrderByStartAtDesc(orgId, page)
                    : meetingRepository.findByOrganizationIdAndOrganizerIdOrderByStartAtDesc(orgId, actor.getId(), page);
        }
        List<Meeting> filtered = all.stream()
                .filter(m -> permissionService.can(actor, Action.VIEW, ResourceType.MEETING,
                        m.getOrganizationId(), m.getOrganizerId()))
                .collect(Collectors.toList());
        return Map.of("count", filtered.size(), "data", filtered);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Meeting> get(@PathVariable Long id) {
        Meeting m = meetingRepository.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
        if (m == null) return ResponseEntity.notFound().build();
        permissionService.require(currentUser.get(), Action.VIEW, ResourceType.MEETING,
                m.getOrganizationId(), m.getOrganizerId());
        return ResponseEntity.ok(m);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.MEETING,
                actor.getOrganizationId(), null);
        Meeting body = bind(raw);
        if (body.getTitle() == null || body.getTitle().isBlank()) return badRequest("title is required");
        if (body.getStartAt() == null) return badRequest("startAt is required");
        if (body.getStatus() != null && !STATUSES.contains(body.getStatus())) return badRequest("invalid status");

        Long organizerId = body.getOrganizerId() != null ? body.getOrganizerId() : actor.getId();
        if (!organizerId.equals(actor.getId())
                && userRepository.findByIdAndOrganizationId(organizerId, actor.getOrganizationId()).isEmpty()) {
            return badRequest("organizer not found in your organization");
        }
        String relatedType = body.getRelatedType() == null || body.getRelatedType().isBlank() ? null : body.getRelatedType();
        String relatedError = validateRelated(relatedType, body.getRelatedId(), actor.getOrganizationId());
        if (relatedError != null) return badRequest(relatedError);

        Meeting m = new Meeting();
        m.setOrganizationId(actor.getOrganizationId());
        m.setTitle(body.getTitle());
        m.setDescription(body.getDescription());
        m.setLocation(body.getLocation());
        m.setMeetingUrl(body.getMeetingUrl());
        m.setStatus(body.getStatus() != null ? body.getStatus() : "scheduled");
        m.setStartAt(body.getStartAt());
        m.setEndAt(body.getEndAt());
        m.setDurationMinutes(body.getDurationMinutes());
        m.setOrganizerId(organizerId);
        m.setRelatedType(relatedType);
        m.setRelatedId(relatedType != null ? body.getRelatedId() : null);
        m.setNotes(body.getNotes());
        m.setOutcome(body.getOutcome());
        m.setCreatedBy(actor.getId());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        meetingRepository.save(m);
        auditService.log("create", "meetings", m.getId(), m.getTitle(), null, m);

        // Notify organizer if different from creator
        if (!organizerId.equals(actor.getId())) {
            notificationService.notify(actor.getOrganizationId(), organizerId,
                    "meeting_scheduled", "Meeting scheduled: " + m.getTitle(),
                    "Meeting on " + m.getStartAt(), "Meeting", m.getId());
        }
        return ResponseEntity.status(201).body(m);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        Meeting existing = meetingRepository.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(currentUser.get(), Action.EDIT, ResourceType.MEETING,
                existing.getOrganizationId(), existing.getOrganizerId());
        Meeting body = bind(raw);
        if (body.getStatus() != null && !STATUSES.contains(body.getStatus())) return badRequest("invalid status");

        if (body.getTitle() != null && !body.getTitle().isBlank()) existing.setTitle(body.getTitle());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        if (body.getLocation() != null) existing.setLocation(body.getLocation());
        if (body.getMeetingUrl() != null) existing.setMeetingUrl(body.getMeetingUrl());
        if (body.getStatus() != null) existing.setStatus(body.getStatus());
        if (body.getStartAt() != null) existing.setStartAt(body.getStartAt());
        if (body.getEndAt() != null) existing.setEndAt(body.getEndAt());
        if (body.getDurationMinutes() != null) existing.setDurationMinutes(body.getDurationMinutes());
        if (body.getNotes() != null) existing.setNotes(body.getNotes());
        if (body.getOutcome() != null) existing.setOutcome(body.getOutcome());
        existing.setUpdatedAt(Instant.now());
        meetingRepository.save(existing);
        auditService.log("update", "meetings", id, existing.getTitle(), null, existing);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Meeting existing = meetingRepository.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(currentUser.get(), Action.DELETE, ResourceType.MEETING,
                existing.getOrganizationId(), existing.getOrganizerId());
        existing.setDeletedAt(Instant.now());
        existing.setDeletedBy(currentUser.userId());
        meetingRepository.save(existing);
        auditService.log("delete", "meetings", id, existing.getTitle(), existing, null);
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
            default -> false;
        };
        return exists ? null : "related record not found";
    }

    private static ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private Meeting bind(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, Meeting.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
