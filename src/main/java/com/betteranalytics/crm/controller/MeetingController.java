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
import com.betteranalytics.crm.security.UrlFields;
import com.betteranalytics.crm.service.Paging;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Filtering, sorting and paging happen in the database. ORG-scope viewers
     * page over every meeting; everyone else over meetings they organize, so a
     * page is always full and {@code count} is the real total.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String relatedType,
                                     @RequestParam(required = false) Long relatedId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        permissionService.require(actor, Action.VIEW, ResourceType.MEETING, orgId, actor.getId());
        boolean orgWide = permissionService.can(actor, Action.VIEW, ResourceType.MEETING, orgId, Long.MIN_VALUE);
        Specification<Meeting> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("organizationId"), orgId));
            if (!orgWide) ps.add(cb.equal(root.get("organizerId"), actor.getId()));
            if (relatedType != null && relatedId != null) {
                ps.add(cb.equal(root.get("relatedType"), relatedType));
                ps.add(cb.equal(root.get("relatedId"), relatedId));
            }
            if (status != null && !status.isBlank()) ps.add(cb.equal(root.get("status"), status));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("startAt"), from));
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("startAt"), to));
            if (q != null && !q.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("title")),
                        "%" + ProjectController.escapeLike(q.trim().toLowerCase(java.util.Locale.ROOT)) + "%", '\\'));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Meeting> result = meetingRepository.findAll(spec, Paging.of(page, size, limit, 100, 500,
                Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", result.getTotalElements());
        out.put("data", result.getContent());
        return out;
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
    @Transactional
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
        m.setMeetingUrl(UrlFields.normalize("meetingUrl", body.getMeetingUrl()));
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
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        Meeting existing = meetingRepository.findByIdAndOrganizationId(id, currentUser.organizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(currentUser.get(), Action.EDIT, ResourceType.MEETING,
                existing.getOrganizationId(), existing.getOrganizerId());
        Meeting body = bind(raw);
        // status has a field default ("scheduled") � only an explicitly sent value counts.
        String newStatus = raw.containsKey("status") ? body.getStatus() : null;
        if (newStatus != null && !STATUSES.contains(newStatus)) return badRequest("invalid status");

        if (body.getTitle() != null && !body.getTitle().isBlank()) existing.setTitle(body.getTitle());
        if (raw.containsKey("description")) existing.setDescription(body.getDescription());
        if (raw.containsKey("location")) existing.setLocation(body.getLocation());
        if (raw.containsKey("meetingUrl")) existing.setMeetingUrl(UrlFields.normalize("meetingUrl", body.getMeetingUrl()));
        if (newStatus != null) existing.setStatus(newStatus);
        if (body.getStartAt() != null) existing.setStartAt(body.getStartAt());
        if (raw.containsKey("endAt")) existing.setEndAt(body.getEndAt());
        if (raw.containsKey("durationMinutes")) existing.setDurationMinutes(body.getDurationMinutes());
        if (raw.containsKey("notes")) existing.setNotes(body.getNotes());
        if (raw.containsKey("outcome")) existing.setOutcome(body.getOutcome());
        existing.setUpdatedAt(Instant.now());
        meetingRepository.save(existing);
        auditService.log("update", "meetings", id, existing.getTitle(), null, existing);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{id}")
    @Transactional
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
