package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.TenantGuard;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Read-only view of the audit log, scoped by the AUDIT_LOG feature rules:
 *   SUPER_ADMIN / VP_SALES → every event in the organization (Scope.ORG)
 *   MANAGER                → events performed by users in their team — the
 *                            reporting tree from PermissionService (Scope.TEAM)
 *   everyone else          → 403
 *
 * Filtering, sorting and pagination all happen in the database. The CSV export
 * uses the same filters and scoping, is streamed in pages (never the whole log
 * in memory), neutralises spreadsheet formula injection, and is itself audited.
 */
@RestController
@RequestMapping("/api/audit-events")
public class AuditEventController {

    static final int MAX_PAGE_SIZE = 200;
    static final int MAX_EXPORT_ROWS = 100_000;
    private static final int EXPORT_CHUNK = 1_000;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AuditEventRepository auditEventRepository;
    private final CurrentUser          currentUser;
    private final PermissionService    permissionService;
    private final AuditService         auditService;

    public AuditEventController(AuditEventRepository auditEventRepository, CurrentUser currentUser,
                                PermissionService permissionService, AuditService auditService) {
        this.auditEventRepository = auditEventRepository;
        this.currentUser          = currentUser;
        this.permissionService    = permissionService;
        this.auditService         = auditService;
    }

    /**
     * Every filter is optional and they combine (AND). Pass page (0-based) and
     * size (max 200) for pagination; the legacy {@code limit} parameter (first
     * N rows, max 1000) still works when page is absent.
     *
     * from/to accept an ISO date (2026-01-31, whole UTC day) or an ISO instant
     * (2026-01-31T10:15:00Z). "to" is inclusive. q searches the record name,
     * record id and actor name (case-insensitive).
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q) {

        Specification<AuditEvent> spec = scopedSpec(new Filters(resource, userId, action, from, to, q));
        PageRequest pageable = page != null
                ? PageRequest.of(Math.max(0, page), clampSize(size), NEWEST_FIRST)
                : PageRequest.of(0, TenantGuard.clampLimit(limit), NEWEST_FIRST);
        Page<AuditEvent> result = auditEventRepository.findAll(spec, pageable);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", result.getTotalElements());
        body.put("data", result.getContent());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    /** Distinct resources and actions present in the caller's visible log — for filter dropdowns. */
    @GetMapping("/facets")
    public Map<String, Object> facets() {
        Scoping s = scoping();
        List<String> resources = s.userIds == null
                ? auditEventRepository.distinctResources(s.orgId)
                : s.userIds.isEmpty() ? List.of() : auditEventRepository.distinctResourcesForUsers(s.orgId, s.userIds);
        List<String> actions = s.userIds == null
                ? auditEventRepository.distinctActions(s.orgId)
                : s.userIds.isEmpty() ? List.of() : auditEventRepository.distinctActionsForUsers(s.orgId, s.userIds);
        return Map.of("resources", resources, "actions", actions);
    }

    /** CSV of the filtered, scoped log (newest first, at most 100,000 rows), streamed page by page. */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            HttpServletResponse response) throws IOException {

        Filters filters = new Filters(resource, userId, action, from, to, q);
        Specification<AuditEvent> spec = scopedSpec(filters);
        long total = Math.min(auditEventRepository.count(spec), MAX_EXPORT_ROWS);

        // Audit the export itself before streaming (an export is a data-access event).
        Map<String, Object> exportInfo = new LinkedHashMap<>();
        exportInfo.put("rows", total);
        exportInfo.put("filters", filters.describe());
        auditService.log("export", "audit_events", null, "Audit log CSV export", null, exportInfo);

        String filename = "audit-log-" + LocalDate.now(ZoneOffset.UTC) + ".csv";
        response.setStatus(200);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Total-Count", String.valueOf(total));

        Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        w.write('﻿'); // BOM so Excel reads UTF-8 names correctly
        w.write("id,timestamp_utc,actor_id,actor_name,action,resource,record_id,record_name,ip_address,user_agent\r\n");
        int written = 0;
        for (int p = 0; written < total; p++) {
            Page<AuditEvent> chunk = auditEventRepository.findAll(spec, PageRequest.of(p, EXPORT_CHUNK, NEWEST_FIRST));
            if (chunk.isEmpty()) break;
            for (AuditEvent e : chunk.getContent()) {
                if (written >= total) break;
                w.write(row(e));
                written++;
            }
            w.flush();
            if (!chunk.hasNext()) break;
        }
        w.flush();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        Scoping s = scoping();
        AuditEvent event = auditEventRepository.findById(id).orElse(null);
        if (event == null || !s.orgId.equals(event.getOrganizationId())
                || (s.userIds != null && (event.getUserId() == null || !s.userIds.contains(event.getUserId())))) {
            throw new NoSuchElementException("audit event not found");
        }
        return ResponseEntity.ok(event);
    }

    // ── scoping ──────────────────────────────────────────────────────────────

    /** userIds == null → the whole organization; otherwise only events by these users. */
    private record Scoping(Long orgId, Set<Long> userIds) {}

    private Scoping scoping() {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        if (hasOrgWideScope(actor, orgId)) {
            return new Scoping(orgId, null);
        }
        // TEAM scope: the check is made against the actor's own id (always in their scope),
        // which fails only when the role has no AUDIT_LOG access at all.
        permissionService.require(actor, Action.VIEW, ResourceType.AUDIT_LOG, orgId, actor.getId());
        return new Scoping(orgId, permissionService.teamScopeUserIds(actor));
    }

    /**
     * ORG scope ⇔ the rule allows viewing an event by someone outside any team.
     * A synthetic owner id that can never be a real user (-1) is never in a
     * TEAM/OWN scope, so only ORG-scoped roles pass.
     */
    private boolean hasOrgWideScope(User actor, Long orgId) {
        return permissionService.can(actor, Action.VIEW, ResourceType.AUDIT_LOG, orgId, -1L);
    }

    private Specification<AuditEvent> scopedSpec(Filters f) {
        Scoping s = scoping();
        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = parseBound(f.from, false);
            toInstant   = parseBound(f.to, true);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("from/to must be an ISO date (YYYY-MM-DD) or ISO instant");
        }
        if (fromInstant != null && toInstant != null && toInstant.isBefore(fromInstant)) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        if (f.q != null && f.q.length() > 200) throw new IllegalArgumentException("q is too long");
        String like = f.q == null || f.q.isBlank() ? null
                : "%" + f.q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("organizationId"), s.orgId)); // tenant filter is never optional
            if (s.userIds != null) {
                p.add(s.userIds.isEmpty() ? cb.disjunction() : root.get("userId").in(s.userIds));
            }
            if (f.resource != null && !f.resource.isBlank()) p.add(cb.equal(root.get("resource"), f.resource.trim()));
            if (f.userId != null)                             p.add(cb.equal(root.get("userId"), f.userId));
            if (f.action != null && !f.action.isBlank())     p.add(cb.equal(root.get("action"), f.action.trim()));
            if (fromInstant != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInstant));
            if (toInstant != null)   p.add(cb.lessThanOrEqualTo(root.get("createdAt"), toInstant));
            if (like != null) {
                p.add(cb.or(
                        cb.like(cb.lower(cb.coalesce(root.<String>get("resourceName"), "")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("resourceId"), "")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("userName"), "")), like, '\\')));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    private record Filters(String resource, Long userId, String action, String from, String to, String q) {
        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (resource != null && !resource.isBlank()) m.put("resource", resource);
            if (userId != null) m.put("userId", userId);
            if (action != null && !action.isBlank()) m.put("action", action);
            if (from != null && !from.isBlank()) m.put("from", from);
            if (to != null && !to.isBlank()) m.put("to", to);
            if (q != null && !q.isBlank()) m.put("q", q);
            return m;
        }
    }

    private static int clampSize(Integer size) {
        if (size == null) return 50;
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
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

    // ── CSV ──────────────────────────────────────────────────────────────────

    private static String row(AuditEvent e) {
        return String.join(",",
                csv(e.getId()), csv(e.getCreatedAt()), csv(e.getUserId()), csv(e.getUserName()),
                csv(e.getAction()), csv(e.getResource()), csv(e.getResourceId()), csv(e.getResourceName()),
                csv(e.getIpAddress()), csv(e.getUserAgent())) + "\r\n";
    }

    /**
     * RFC 4180 quoting plus spreadsheet formula-injection defence: a cell that
     * starts with = + - @ TAB or CR is prefixed with an apostrophe so Excel /
     * Sheets show it as text instead of evaluating it.
     */
    static String csv(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") || s.startsWith("'")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
