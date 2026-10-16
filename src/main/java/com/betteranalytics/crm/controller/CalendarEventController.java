package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.CalendarEvent;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.Paging;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET    /api/calendar                               — list; ?from=&to= (ISO, overlap), ?eventType=, ?ownerId=, ?page=&size=
 * GET    /api/calendar?relatedType=Lead&relatedId=5  — events for a CRM record
 * GET    /api/calendar/{id}                          — get
 * POST   /api/calendar                               — create
 * PUT    /api/calendar/{id}                          — partial update (only the keys sent change)
 * DELETE /api/calendar/{id}                          — delete
 *
 * Access: PermissionService on CALENDAR_EVENT with the event owner as record
 * owner — Manager+ see and manage every calendar, reps (and SUPPORT, read-only)
 * only their own. Creating an event for someone else needs EDIT scope on them.
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarEventController {

    private static final Set<String> EVENT_TYPES = Set.of("event", "meeting", "reminder", "task", "deadline");
    private static final Set<String> RELATED_TYPES = Set.of("Lead", "Opportunity", "Contact", "Company", "Task", "Meeting");

    private final CalendarEventRepository repo;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepo;
    private final LeadRepository leadRepo;
    private final OpportunityRepository opportunityRepo;
    private final ContactRepository contactRepo;
    private final CompanyRepository companyRepo;
    private final TaskRepository taskRepo;
    private final MeetingRepository meetingRepo;

    public CalendarEventController(CalendarEventRepository repo, CurrentUser currentUser,
                                   PermissionService permissionService, AuditService auditService,
                                   ObjectMapper objectMapper,
                                   UserRepository userRepo, LeadRepository leadRepo,
                                   OpportunityRepository opportunityRepo, ContactRepository contactRepo,
                                   CompanyRepository companyRepo, TaskRepository taskRepo,
                                   MeetingRepository meetingRepo) {
        this.repo = repo;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.userRepo = userRepo;
        this.leadRepo = leadRepo;
        this.opportunityRepo = opportunityRepo;
        this.contactRepo = contactRepo;
        this.companyRepo = companyRepo;
        this.taskRepo = taskRepo;
        this.meetingRepo = meetingRepo;
    }

    @GetMapping
    public List<CalendarEvent> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String relatedType,
            @RequestParam(required = false) Long relatedId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        permissionService.require(actor, Action.VIEW, ResourceType.CALENDAR_EVENT, orgId, actor.getId());
        // ORG-scope viewers see every calendar; everyone else only events they own.
        boolean orgWide = permissionService.can(actor, Action.VIEW, ResourceType.CALENDAR_EVENT, orgId, Long.MIN_VALUE);
        Long ownerFilter = orgWide ? ownerId : actor.getId();
        Specification<CalendarEvent> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("organizationId"), orgId));
            if (ownerFilter != null) ps.add(cb.equal(root.get("ownerId"), ownerFilter));
            if (relatedType != null && relatedId != null) {
                ps.add(cb.equal(root.get("relatedType"), relatedType));
                ps.add(cb.equal(root.get("relatedId"), relatedId));
            }
            if (eventType != null && !eventType.isBlank()) ps.add(cb.equal(root.get("eventType"), eventType));
            // Overlap with [from, to]: multi-day events that started before `from` still show.
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("startAt"), to));
            if (from != null) {
                Expression<Instant> end = cb.coalesce(root.get("endAt"), root.get("startAt"));
                ps.add(cb.greaterThanOrEqualTo(end, from));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return repo.findAll(spec, Paging.of(page, size, 2000, 2000,
                Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")))).getContent();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CalendarEvent> get(@PathVariable Long id) {
        User actor = currentUser.get();
        CalendarEvent e = repo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (e == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.CALENDAR_EVENT, e.getOrganizationId(), e.getOwnerId());
        return ResponseEntity.ok(e);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.CALENDAR_EVENT, actor.getOrganizationId(), null);
        CalendarEvent body = bind(raw, CalendarEvent.class);
        if (body.getTitle() == null || body.getTitle().isBlank()) return badRequest("title is required");
        if (body.getStartAt() == null) return badRequest("startAt is required");
        if (body.getEndAt() != null && body.getEndAt().isBefore(body.getStartAt())) return badRequest("endAt must not be before startAt");
        if (body.getEventType() != null && !EVENT_TYPES.contains(body.getEventType())) return badRequest("invalid eventType");

        Long ownerId = body.getOwnerId() != null ? body.getOwnerId() : actor.getId();
        if (!ownerId.equals(actor.getId())) {
            if (userRepo.findByIdAndOrganizationId(ownerId, actor.getOrganizationId()).isEmpty()) {
                return badRequest("owner not found in your organization");
            }
            // Putting an event on someone else's calendar = editing their calendar.
            permissionService.require(actor, Action.EDIT, ResourceType.CALENDAR_EVENT, actor.getOrganizationId(), ownerId);
        }
        String relatedError = validateRelated(body.getRelatedType(), body.getRelatedId(), actor.getOrganizationId());
        if (relatedError != null) return badRequest(relatedError);

        CalendarEvent e = new CalendarEvent();
        e.setOrganizationId(actor.getOrganizationId());
        e.setTitle(body.getTitle().trim());
        e.setDescription(body.getDescription());
        e.setStartAt(body.getStartAt());
        e.setEndAt(body.getEndAt());
        e.setAllDay(body.isAllDay());
        e.setEventType(body.getEventType() != null ? body.getEventType() : "event");
        e.setRelatedType(blankToNull(body.getRelatedType()));
        e.setRelatedId(e.getRelatedType() != null ? body.getRelatedId() : null);
        e.setColorHex(body.getColorHex());
        e.setLocation(body.getLocation());
        e.setRecurrenceRule(body.getRecurrenceRule());
        e.setOwnerId(ownerId);
        e.setCreatedBy(actor.getId());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        CalendarEvent saved = repo.save(e);
        auditService.log("create", "calendar_events", saved.getId(), saved.getTitle(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        CalendarEvent existing = repo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.CALENDAR_EVENT, existing.getOrganizationId(), existing.getOwnerId());
        CalendarEvent body = bind(raw, CalendarEvent.class);
        // eventType has a field default — only an explicitly sent value counts.
        String newType = raw.containsKey("eventType") ? body.getEventType() : null;
        if (newType != null && !EVENT_TYPES.contains(newType)) return badRequest("invalid eventType");
        if (body.getOwnerId() != null && !body.getOwnerId().equals(existing.getOwnerId())) {
            if (userRepo.findByIdAndOrganizationId(body.getOwnerId(), actor.getOrganizationId()).isEmpty()) {
                return badRequest("owner not found in your organization");
            }
            permissionService.require(actor, Action.EDIT, ResourceType.CALENDAR_EVENT, actor.getOrganizationId(), body.getOwnerId());
            existing.setOwnerId(body.getOwnerId());
        }
        if (raw.containsKey("relatedType") || raw.containsKey("relatedId")) {
            String type = raw.containsKey("relatedType") ? blankToNull(body.getRelatedType()) : existing.getRelatedType();
            Long relId = raw.containsKey("relatedId") ? body.getRelatedId() : existing.getRelatedId();
            String relatedError = validateRelated(type, relId, actor.getOrganizationId());
            if (relatedError != null) return badRequest(relatedError);
            existing.setRelatedType(type);
            existing.setRelatedId(type != null ? relId : null);
        }
        if (body.getTitle() != null && !body.getTitle().isBlank()) existing.setTitle(body.getTitle().trim());
        if (raw.containsKey("description"))    existing.setDescription(body.getDescription());
        if (body.getStartAt()        != null) existing.setStartAt(body.getStartAt());
        if (raw.containsKey("endAt"))         existing.setEndAt(body.getEndAt());
        if (newType != null)                 existing.setEventType(newType);
        if (raw.containsKey("location"))       existing.setLocation(body.getLocation());
        if (raw.containsKey("colorHex"))       existing.setColorHex(body.getColorHex());
        if (raw.containsKey("recurrenceRule")) existing.setRecurrenceRule(body.getRecurrenceRule());
        if (raw.containsKey("allDay"))        existing.setAllDay(body.isAllDay());
        if (existing.getEndAt() != null && existing.getEndAt().isBefore(existing.getStartAt())) {
            return badRequest("endAt must not be before startAt");
        }
        existing.setUpdatedAt(Instant.now());
        CalendarEvent saved = repo.save(existing);
        auditService.log("update", "calendar_events", id, saved.getTitle(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        CalendarEvent existing = repo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.CALENDAR_EVENT, existing.getOrganizationId(), existing.getOwnerId());
        repo.delete(existing); // calendar_attendees cascade
        auditService.log("delete", "calendar_events", id, existing.getTitle(), existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns an error message when relatedType/relatedId don't point at a record in the caller's org. */
    private String validateRelated(String relatedType, Long relatedId, Long orgId) {
        if (relatedType == null || relatedType.isBlank()) return null;
        if (!RELATED_TYPES.contains(relatedType)) return "unsupported relatedType";
        if (relatedId == null) return "relatedId is required with relatedType";
        boolean exists = switch (relatedType) {
            case "Lead" -> leadRepo.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "Opportunity" -> opportunityRepo.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "Contact" -> contactRepo.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "Company" -> companyRepo.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "Task" -> taskRepo.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            case "Meeting" -> meetingRepo.findByIdAndOrganizationId(relatedId, orgId).isPresent();
            default -> false;
        };
        return exists ? null : "related record not found";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private <T> T bind(Map<String, Object> raw, Class<T> type) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
