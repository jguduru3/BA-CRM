package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.CalendarEvent;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GET    /api/calendar                               — list (optional ?from=&to= ISO range)
 * GET    /api/calendar/{id}                          — get
 * POST   /api/calendar                               — create
 * PUT    /api/calendar/{id}                          — update
 * DELETE /api/calendar/{id}                          — delete
 * GET    /api/calendar?relatedType=Lead&relatedId=5  — events for a CRM record
 *
 * Events are visible org-wide (shared team calendar). Only the event owner,
 * its creator, or a Manager+ may change or delete it, and only a Manager+ can
 * create an event owned by someone else.
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarEventController {

    private static final Set<String> EVENT_TYPES = Set.of("event", "meeting", "reminder", "task", "deadline");
    private static final Set<String> RELATED_TYPES = Set.of("Lead", "Opportunity", "Contact", "Company", "Task", "Meeting");

    private final CalendarEventRepository repo;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepo;
    private final LeadRepository leadRepo;
    private final OpportunityRepository opportunityRepo;
    private final ContactRepository contactRepo;
    private final CompanyRepository companyRepo;
    private final TaskRepository taskRepo;
    private final MeetingRepository meetingRepo;

    public CalendarEventController(CalendarEventRepository repo, CurrentUser currentUser, ObjectMapper objectMapper,
                                   UserRepository userRepo, LeadRepository leadRepo,
                                   OpportunityRepository opportunityRepo, ContactRepository contactRepo,
                                   CompanyRepository companyRepo, TaskRepository taskRepo,
                                   MeetingRepository meetingRepo) {
        this.repo = repo;
        this.currentUser = currentUser;
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
            @RequestParam(required = false) Long relatedId) {
        Long orgId = currentUser.organizationId();
        if (relatedType != null && relatedId != null) {
            return repo.findByOrganizationIdAndRelatedTypeAndRelatedId(orgId, relatedType, relatedId);
        }
        if (from != null && to != null) {
            return repo.findByOrganizationIdAndDateRange(orgId, from, to);
        }
        return repo.findByOrganizationIdOrderByStartAtAsc(orgId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CalendarEvent> get(@PathVariable Long id) {
        return repo.findByIdAndOrganizationId(id, currentUser.organizationId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        CalendarEvent body = bind(raw, CalendarEvent.class);
        if (body.getTitle() == null || body.getTitle().isBlank()) return badRequest("title is required");
        if (body.getStartAt() == null) return badRequest("startAt is required");
        if (body.getEventType() != null && !EVENT_TYPES.contains(body.getEventType())) return badRequest("invalid eventType");

        Long ownerId = body.getOwnerId() != null ? body.getOwnerId() : actor.getId();
        if (!ownerId.equals(actor.getId())) {
            if (!isManagerPlus(actor)) throw new AccessDeniedException("only managers can create events for other users");
            if (userRepo.findByIdAndOrganizationId(ownerId, actor.getOrganizationId()).isEmpty()) {
                return badRequest("owner not found in your organization");
            }
        }
        String relatedError = validateRelated(body.getRelatedType(), body.getRelatedId(), actor.getOrganizationId());
        if (relatedError != null) return badRequest(relatedError);

        CalendarEvent e = new CalendarEvent();
        e.setOrganizationId(actor.getOrganizationId());
        e.setTitle(body.getTitle());
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
        return ResponseEntity.status(201).body(repo.save(e));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        CalendarEvent existing = repo.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanModify(actor, existing);
        CalendarEvent body = bind(raw, CalendarEvent.class);
        if (body.getEventType() != null && !EVENT_TYPES.contains(body.getEventType())) return badRequest("invalid eventType");
        if (body.getTitle() != null && !body.getTitle().isBlank()) existing.setTitle(body.getTitle());
        if (body.getDescription()    != null) existing.setDescription(body.getDescription());
        if (body.getStartAt()        != null) existing.setStartAt(body.getStartAt());
        if (raw.containsKey("endAt"))         existing.setEndAt(body.getEndAt());
        if (body.getEventType()      != null) existing.setEventType(body.getEventType());
        if (body.getLocation()       != null) existing.setLocation(body.getLocation());
        if (body.getColorHex()       != null) existing.setColorHex(body.getColorHex());
        if (body.getRecurrenceRule() != null) existing.setRecurrenceRule(body.getRecurrenceRule());
        if (raw.containsKey("allDay"))        existing.setAllDay(body.isAllDay());
        existing.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(repo.save(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        CalendarEvent existing = repo.findByIdAndOrganizationId(id, actor.getOrganizationId())
                .orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanModify(actor, existing);
        repo.delete(existing);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void requireCanModify(User actor, CalendarEvent e) {
        if (!actor.getId().equals(e.getOwnerId()) && !actor.getId().equals(e.getCreatedBy()) && !isManagerPlus(actor)) {
            throw new AccessDeniedException("only the event owner or a manager can change this event");
        }
    }

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

    private static boolean isManagerPlus(User u) {
        return u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES || u.getRole() == Role.MANAGER;
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
