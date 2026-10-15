package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.Aggregates;
import com.betteranalytics.crm.core.CrmMasks;
import com.betteranalytics.crm.core.ScopeSpecs;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/**
 * Streamed CSV export of the core CRM objects:
 *   GET /api/exports/{leads|opportunities|contacts|companies|activities|tasks}
 *   GET /api/export/{...}  (legacy alias)
 *
 * - Open to every role that can VIEW the object type (Support included —
 *   read-only data out); rows are limited to the caller's record scope in SQL.
 * - Field masks apply exactly as on screen (lead contact info after hand-off,
 *   opportunity value / probability / PO status, activity bodies).
 * - Streamed: rows are read from the database in id-ordered chunks
 *   ({@value #CHUNK} at a time, keyset pagination) and written straight to the
 *   servlet output stream — the export is never held in memory. (Synchronous on
 *   purpose: a StreamingResponseBody would complete on an ASYNC re-dispatch, which
 *   the security filter chain doesn't authenticate.)
 * - Cells starting with = + - @ (or tab / CR) are prefixed with ' against
 *   spreadsheet formula injection; the file starts with a UTF-8 BOM.
 * - Every export is audited (action "export") with the row count.
 */
@RestController
@RequestMapping({"/api/exports", "/api/export"})
public class ExportController {

    static final int CHUNK = 500;
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final TaskRepository taskRepository;
    private final ActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final ScopeSpecs scopes;
    private final Aggregates aggregates;
    private final CrmMasks masks;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public ExportController(LeadRepository leadRepository, OpportunityRepository opportunityRepository,
                            CompanyRepository companyRepository, ContactRepository contactRepository,
                            TaskRepository taskRepository, ActivityRepository activityRepository,
                            UserRepository userRepository, PermissionService permissionService,
                            ScopeSpecs scopes, Aggregates aggregates, CrmMasks masks,
                            AuditService auditService, CurrentUser currentUser) {
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.taskRepository = taskRepository;
        this.activityRepository = activityRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.scopes = scopes;
        this.aggregates = aggregates;
        this.masks = masks;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    /** One export type: its scoped query, header and row mapper (masks applied). */
    private record Plan<T>(Class<T> type, JpaSpecificationExecutor<T> repo, Specification<T> spec,
                           List<String> header, Function<T, List<Object>> row) {}

    @GetMapping("/{type}")
    public void export(@PathVariable String type, HttpServletResponse response) throws IOException {
        User actor = currentUser.get();
        String name = type.endsWith(".csv") ? type.substring(0, type.length() - 4) : type;
        Map<Long, String> userNames = new HashMap<>();
        userRepository.findByOrganizationId(actor.getOrganizationId()).forEach(u -> userNames.put(u.getId(), u.getName()));
        Function<Long, String> userName = id -> id == null ? null : userNames.get(id);

        Plan<?> plan = switch (name) {
            case "leads" -> new Plan<>(Lead.class, leadRepository, require(scopes.leads(actor), name),
                    List.of("id", "name", "email", "phone", "businessName", "stage", "source", "priority", "value",
                            "leadType", "category", "industry", "geography", "leadScore", "followUpAt",
                            "companyId", "owner", "createdAt", "updatedAt"),
                    raw -> {
                        Lead l = masks.lead(actor, raw);
                        return Arrays.asList(l.getId(), l.getName(), l.getEmail(), l.getPhone(), l.getBusinessName(),
                                l.getStage(), l.getSource(), l.getPriority(), l.getValue(), l.getLeadType(),
                                l.getCategory(), l.getIndustry(), l.getGeography(), l.getLeadScore(), l.getFollowUpAt(),
                                l.getCompanyId(), userName.apply(l.getOwnerId()), l.getCreatedAt(), l.getUpdatedAt());
                    });
            case "opportunities" -> {
                // Resolve the standard VIEW scope up front: the stream runs off the request
                // thread, where per-row permission lookups would hit the database each time.
                OwnerScope standard = permissionService.ownerScope(actor, Action.VIEW, ResourceType.OPPORTUNITY);
                yield new Plan<>(Opportunity.class, opportunityRepository, require(scopes.opportunities(actor), name),
                        List.of("id", "name", "stage", "value", "probability", "closeDate", "closeReason", "serviceProduct",
                                "proposalStatus", "negotiationStatus", "poStatus", "companyId", "contactId", "owner",
                                "createdAt", "updatedAt"),
                        raw -> {
                            Opportunity o = inScope(standard, raw.getOwnerId())
                                    ? masks.opportunity(actor, raw) : masks.sourcedOnly(raw);
                            return Arrays.asList(o.getId(), o.getName(), o.getStage(), o.getValue(), o.getProbability(),
                                    o.getCloseDate(), o.getCloseReason(), o.getServiceProduct(), o.getProposalStatus(),
                                    o.getNegotiationStatus(), o.getPoStatus(), o.getCompanyId(), o.getContactId(),
                                    userName.apply(o.getOwnerId()), o.getCreatedAt(), o.getUpdatedAt());
                        });
            }
            case "companies" -> new Plan<>(Company.class, companyRepository, require(scopes.companies(actor), name),
                    List.of("id", "name", "industry", "size", "website", "phone", "email", "location", "revenue",
                            "status", "owner", "createdAt", "updatedAt"),
                    c -> Arrays.asList(c.getId(), c.getName(), c.getIndustry(), c.getSize(), c.getWebsite(), c.getPhone(),
                            c.getEmail(), c.getLocation(), c.getRevenue(), c.getStatus(), userName.apply(c.getOwnerId()),
                            c.getCreatedAt(), c.getUpdatedAt()));
            case "contacts" -> new Plan<>(Contact.class, contactRepository, require(scopes.contacts(actor), name),
                    List.of("id", "name", "title", "email", "phone", "companyId", "status", "source", "industry",
                            "country", "state", "linkedin", "owner", "createdAt", "updatedAt"),
                    c -> Arrays.asList(c.getId(), c.getName(), c.getTitle(), c.getEmail(), c.getPhone(), c.getCompanyId(),
                            c.getStatus(), c.getSource(), c.getIndustry(), c.getCountry(), c.getState(), c.getLinkedin(),
                            userName.apply(c.getOwnerId()), c.getCreatedAt(), c.getUpdatedAt()));
            case "activities" -> new Plan<>(Activity.class, activityRepository, require(scopes.activities(actor), name),
                    List.of("id", "type", "subject", "body", "outcome", "relatedType", "relatedId", "author",
                            "durationMinutes", "correctionForId", "scheduledAt", "completedAt", "createdAt"),
                    raw -> {
                        Activity a = masks.activity(actor, raw);
                        return Arrays.asList(a.getId(), a.getType(), a.getSubject(), a.getBody(), a.getOutcome(),
                                a.getRelatedType(), a.getRelatedId(), userName.apply(a.getUserId()), a.getDurationMinutes(),
                                a.getCorrectionForId(), a.getScheduledAt(), a.getCompletedAt(), a.getCreatedAt());
                    });
            case "tasks" -> new Plan<>(Task.class, taskRepository, require(scopes.tasks(actor), name),
                    List.of("id", "title", "status", "priority", "dueDate", "assignee", "relatedType", "relatedId",
                            "completedAt", "createdAt"),
                    t -> Arrays.asList(t.getId(), t.getTitle(), t.getStatus(), t.getPriority(), t.getDueDate(),
                            userName.apply(t.getAssigneeId()), t.getRelatedType(), t.getRelatedId(),
                            t.getCompletedAt(), t.getCreatedAt()));
            default -> throw new NoSuchElementException("unknown export type: " + name);
        };

        long rows = countRows(plan);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rows", rows);
        details.put("type", name);
        auditService.log("export", name, null, name + " (" + rows + " rows)", null, details);

        String file = name + "-" + LocalDate.now() + ".csv";
        response.setStatus(200);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Export-Rows", String.valueOf(rows));
        // Written chunk by chunk straight to the servlet output stream on the request
        // thread (still authenticated, no async re-dispatch); nothing is buffered beyond
        // one chunk of rows.
        stream(plan, response.getOutputStream());
        response.flushBuffer();
    }

    private static <T> Specification<T> require(Specification<T> spec, String type) {
        if (spec == null) throw new AccessDeniedException("you don't have access to export " + type);
        return spec;
    }

    private static boolean inScope(OwnerScope s, Long ownerId) {
        if (s.all()) return true;
        if (ownerId == null) return s.includeUnowned();
        return s.ownerIds().contains(ownerId);
    }

    private <T> long countRows(Plan<T> plan) {
        return aggregates.count(plan.type(), plan.spec());
    }

    /** Keyset pagination by id: each chunk is one bounded SELECT, written out immediately. */
    private <T> void stream(Plan<T> plan, OutputStream raw) throws IOException {
        raw.write(BOM);
        Writer w = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8));
        writeRow(w, new ArrayList<>(plan.header()));
        long lastId = 0;
        while (true) {
            long after = lastId;
            List<T> chunk = plan.repo().findBy(plan.spec().and(Specs.idGreaterThan(after)),
                    q -> q.sortBy(Sort.by(Sort.Direction.ASC, "id")).limit(CHUNK).all());
            if (chunk.isEmpty()) break;
            for (T row : chunk) {
                List<Object> values = plan.row().apply(row);
                writeRow(w, values);
                lastId = ((Number) values.get(0)).longValue();
            }
            w.flush();
            if (chunk.size() < CHUNK) break;
        }
        w.flush();
    }

    private static void writeRow(Writer w, List<?> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) w.write(',');
            w.write(cell(values.get(i)));
        }
        w.write("\r\n");
    }

    /** CSV-escapes a value and neutralises spreadsheet formulas (= + - @, tab, CR). */
    static String cell(Object o) {
        if (o == null) return "";
        String s = o.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(o instanceof Number)) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
