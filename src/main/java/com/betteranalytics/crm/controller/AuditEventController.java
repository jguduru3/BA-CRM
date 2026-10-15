package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.TenantGuard;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of the audit log.
 * Visible to SUPER_ADMIN only per the spec ("Audit log visible to Super Admin").
 * VP_SALES can view their own org's log; MANAGER and reps cannot see the log at all.
 */
@RestController
@RequestMapping("/api/audit-events")
public class AuditEventController {

    private final AuditEventRepository auditEventRepository;
    private final CurrentUser          currentUser;

    public AuditEventController(AuditEventRepository auditEventRepository, CurrentUser currentUser) {
        this.auditEventRepository = auditEventRepository;
        this.currentUser          = currentUser;
    }

    /**
     * M6: every filter is optional and they combine (AND) — previously only the
     * first non-null filter was applied and the rest silently ignored. The query
     * runs in the database with the limit applied there (capped at 1000), newest
     * first, rather than loading the org's entire audit history into memory.
     *
     * from/to accept an ISO date (2026-01-31, whole UTC day) or an ISO instant
     * (2026-01-31T10:15:00Z). "to" is inclusive.
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        User actor = currentUser.get();
        requireAuditAccess(actor);

        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = parseBound(from, false);
            toInstant   = parseBound(to, true);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "from/to must be an ISO date (YYYY-MM-DD) or ISO instant"));
        }
        if (fromInstant != null && toInstant != null && toInstant.isBefore(fromInstant)) {
            return ResponseEntity.badRequest().body(Map.of("error", "'to' must not be before 'from'"));
        }

        Long orgId = currentUser.organizationId();
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("organizationId"), orgId)); // tenant filter is never optional
            if (resource != null && !resource.isBlank()) p.add(cb.equal(root.get("resource"), resource));
            if (userId != null)                           p.add(cb.equal(root.get("userId"), userId));
            if (action != null && !action.isBlank())     p.add(cb.equal(root.get("action"), action));
            if (fromInstant != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInstant));
            if (toInstant != null)   p.add(cb.lessThanOrEqualTo(root.get("createdAt"), toInstant));
            return cb.and(p.toArray(new Predicate[0]));
        };

        // Newest first, sorted by the database (no in-memory compareTo on a
        // possibly-null createdAt any more — M13). Every writer (AuditService,
        // PermissionService.logDenial) stamps createdAt; id breaks ties
        // deterministically and orders any legacy null-timestamp rows.
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        Page<AuditEvent> page = auditEventRepository.findAll(spec,
                PageRequest.of(0, TenantGuard.clampLimit(limit), sort));
        return ResponseEntity.ok(Map.of("count", page.getTotalElements(), "data", page.getContent()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        User actor = currentUser.get();
        requireAuditAccess(actor);
        AuditEvent event = auditEventRepository.findById(id).orElse(null);
        if (event == null || !currentUser.organizationId().equals(event.getOrganizationId())) {
            return ResponseEntity.status(404).body(Map.of("error", "audit event not found"));
        }
        return ResponseEntity.ok(event);
    }

    /** Parses an ISO date or instant; a bare date expands to the start (or end) of that UTC day. */
    private static Instant parseBound(String raw, boolean endOfDay) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.length() == 10) {
            LocalDate d = LocalDate.parse(v);
            return endOfDay
                    ? d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusNanos(1)
                    : d.atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(v);
    }

    private void requireAuditAccess(User actor) {
        if (actor.getRole() != Role.SUPER_ADMIN && actor.getRole() != Role.VP_SALES) {
            throw new AccessDeniedException("audit log is visible to Super Admin and VP Sales only");
        }
    }
}
