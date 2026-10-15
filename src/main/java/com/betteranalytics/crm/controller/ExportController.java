package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/**
 * CSV export of the core CRM objects. Manager and above only — bulk export is
 * the classic data-exfiltration path, so it is gated harder than viewing, every
 * export is audited (action "export"), and denials land in the audit log via
 * PermissionService. Rows are still filtered by record scope, cells that start
 * with = + - @ are neutralised against spreadsheet formula injection, and the
 * file starts with a UTF-8 BOM so Excel opens non-ASCII names correctly.
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private static final int MAX_ROWS = 50_000;

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final TaskRepository taskRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public ExportController(LeadRepository leadRepository, OpportunityRepository opportunityRepository,
                            CompanyRepository companyRepository, ContactRepository contactRepository,
                            TaskRepository taskRepository, PermissionService permissionService,
                            AuditService auditService, CurrentUser currentUser) {
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.taskRepository = taskRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{resource}")
    public ResponseEntity<byte[]> export(@PathVariable String resource) {
        User actor = currentUser.get();
        Role r = actor.getRole();
        if (r != Role.SUPER_ADMIN && r != Role.VP_SALES && r != Role.MANAGER) {
            throw new AccessDeniedException("export is available to Managers and above");
        }
        Long org = actor.getOrganizationId();
        String name = resource.endsWith(".csv") ? resource.substring(0, resource.length() - 4) : resource;

        String csv;
        int rows;
        switch (name) {
            case "leads" -> {
                List<Lead> data = leadRepository.findByOrganizationId(org).stream()
                        .filter(l -> permissionService.can(actor, Action.VIEW, ResourceType.LEAD, org, l.getOwnerId()))
                        .limit(MAX_ROWS).toList();
                rows = data.size();
                csv = toCsv(data, List.of("id", "name", "email", "phone", "businessName", "stage", "source", "priority",
                                "value", "leadType", "category", "industry", "geography", "leadScore", "ownerId", "createdAt"),
                        l -> List.of(l.getId(), l.getName(), l.getEmail(), l.getPhone(), l.getBusinessName(), l.getStage(),
                                l.getSource(), l.getPriority(), l.getValue(), l.getLeadType(), l.getCategory(),
                                l.getIndustry(), l.getGeography(), l.getLeadScore(), l.getOwnerId(), l.getCreatedAt()));
            }
            case "opportunities" -> {
                List<Opportunity> data = opportunityRepository.findByOrganizationId(org).stream()
                        .filter(o -> permissionService.can(actor, Action.VIEW, ResourceType.OPPORTUNITY, org, o.getOwnerId()))
                        .limit(MAX_ROWS).toList();
                rows = data.size();
                csv = toCsv(data, List.of("id", "name", "stage", "value", "probability", "closeDate", "serviceProduct",
                                "proposalStatus", "negotiationStatus", "poStatus", "companyId", "contactId", "ownerId", "createdAt"),
                        o -> List.of(o.getId(), o.getName(), o.getStage(), o.getValue(), o.getProbability(), o.getCloseDate(),
                                o.getServiceProduct(), o.getProposalStatus(), o.getNegotiationStatus(), o.getPoStatus(),
                                o.getCompanyId(), o.getContactId(), o.getOwnerId(), o.getCreatedAt()));
            }
            case "companies" -> {
                List<Company> data = companyRepository.findByOrganizationId(org).stream()
                        .filter(c -> permissionService.can(actor, Action.VIEW, ResourceType.COMPANY, org, c.getOwnerId()))
                        .limit(MAX_ROWS).toList();
                rows = data.size();
                csv = toCsv(data, List.of("id", "name", "industry", "size", "website", "phone", "email", "location",
                                "revenue", "status", "ownerId", "createdAt"),
                        c -> List.of(c.getId(), c.getName(), c.getIndustry(), c.getSize(), c.getWebsite(), c.getPhone(),
                                c.getEmail(), c.getLocation(), c.getRevenue(), c.getStatus(), c.getOwnerId(), c.getCreatedAt()));
            }
            case "contacts" -> {
                List<Contact> data = contactRepository.findByOrganizationId(org).stream()
                        .filter(c -> permissionService.can(actor, Action.VIEW, ResourceType.CONTACT, org, c.getOwnerId()))
                        .limit(MAX_ROWS).toList();
                rows = data.size();
                csv = toCsv(data, List.of("id", "name", "title", "email", "phone", "companyId", "status", "source",
                                "industry", "country", "state", "linkedin", "ownerId", "createdAt"),
                        c -> List.of(c.getId(), c.getName(), c.getTitle(), c.getEmail(), c.getPhone(), c.getCompanyId(),
                                c.getStatus(), c.getSource(), c.getIndustry(), c.getCountry(), c.getState(),
                                c.getLinkedin(), c.getOwnerId(), c.getCreatedAt()));
            }
            case "tasks" -> {
                List<Task> data = taskRepository.findByOrganizationId(org).stream()
                        .filter(t -> permissionService.can(actor, Action.VIEW, ResourceType.TASK, org, t.getAssigneeId()))
                        .limit(MAX_ROWS).toList();
                rows = data.size();
                csv = toCsv(data, List.of("id", "title", "status", "priority", "dueDate", "assigneeId", "relatedType",
                                "relatedId", "completedAt", "createdAt"),
                        t -> List.of(t.getId(), t.getTitle(), t.getStatus(), t.getPriority(), t.getDueDate(),
                                t.getAssigneeId(), t.getRelatedType(), t.getRelatedId(), t.getCompletedAt(), t.getCreatedAt()));
            }
            default -> throw new NoSuchElementException("unknown export resource: " + name);
        }

        auditService.log("export", name, null, name + " (" + rows + " rows)", null, Map.of("rows", rows));
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        String file = name + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(out);
    }

    private static <T> String toCsv(List<T> rows, List<String> header, Function<T, List<Object>> values) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", header)).append("\r\n");
        for (T row : rows) {
            List<Object> v = values.apply(row);
            for (int i = 0; i < v.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(cell(v.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

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
