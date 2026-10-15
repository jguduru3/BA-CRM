package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Global search behind the ⌘K command palette: one query across leads,
 * opportunities, companies, contacts and tasks. Every hit is filtered through
 * the same PermissionService VIEW check as the list screens, so search never
 * reveals a record the caller couldn't open — and only display fields
 * (title / subtitle) are returned, never masked contact data.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final TaskRepository taskRepository;
    private final PermissionService permissionService;
    private final CurrentUser currentUser;

    public SearchController(LeadRepository leadRepository, OpportunityRepository opportunityRepository,
                            CompanyRepository companyRepository, ContactRepository contactRepository,
                            TaskRepository taskRepository, PermissionService permissionService,
                            CurrentUser currentUser) {
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.taskRepository = taskRepository;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "6") int limit) {
        String term = q.trim().toLowerCase(Locale.ROOT);
        int per = Math.max(1, Math.min(limit, 20));
        List<Map<String, Object>> results = new ArrayList<>();
        if (term.length() < 2 || term.length() > 100) {
            return Map.of("query", q, "results", results);
        }
        User actor = currentUser.get();
        Long org = actor.getOrganizationId();

        results.addAll(match(leadRepository.findByOrganizationId(org), term, per,
                l -> permissionService.can(actor, Action.VIEW, ResourceType.LEAD, l.getOrganizationId(), l.getOwnerId()),
                l -> Stream.of(l.getName(), l.getBusinessName(), l.getEmail(), l.getPhone()),
                l -> hit("lead", l.getId(), l.getName(), join(l.getBusinessName(), l.getStage()), "/leads?open=" + l.getId())));

        results.addAll(match(opportunityRepository.findByOrganizationId(org), term, per,
                o -> permissionService.can(actor, Action.VIEW, ResourceType.OPPORTUNITY, o.getOrganizationId(), o.getOwnerId())
                        || permissionService.canViewSourcedOpportunity(actor, o),
                o -> Stream.of(o.getName(), o.getServiceProduct()),
                o -> hit("opportunity", o.getId(), o.getName(), o.getStage(), "/opportunities?open=" + o.getId())));

        results.addAll(match(companyRepository.findByOrganizationId(org), term, per,
                c -> permissionService.can(actor, Action.VIEW, ResourceType.COMPANY, c.getOrganizationId(), c.getOwnerId()),
                c -> Stream.of(c.getName(), c.getIndustry(), c.getWebsite(), c.getEmail()),
                c -> hit("company", c.getId(), c.getName(), join(c.getIndustry(), c.getLocation()), "/companies?open=" + c.getId())));

        results.addAll(match(contactRepository.findByOrganizationId(org), term, per,
                c -> permissionService.can(actor, Action.VIEW, ResourceType.CONTACT, c.getOrganizationId(), c.getOwnerId()),
                c -> Stream.of(c.getName(), c.getEmail(), c.getPhone(), c.getTitle()),
                c -> hit("contact", c.getId(), c.getName(), join(c.getTitle(), c.getEmail()), "/contacts?open=" + c.getId())));

        results.addAll(match(taskRepository.findByOrganizationId(org), term, per,
                t -> permissionService.can(actor, Action.VIEW, ResourceType.TASK, t.getOrganizationId(), t.getAssigneeId()),
                t -> Stream.of(t.getTitle()),
                t -> hit("task", t.getId(), t.getTitle(), t.getStatus(), "/tasks?open=" + t.getId())));

        return Map.of("query", q, "results", results);
    }

    private static <T> List<Map<String, Object>> match(List<T> rows, String term, int limit,
                                                       java.util.function.Predicate<T> visible,
                                                       Function<T, Stream<String>> fields,
                                                       Function<T, Map<String, Object>> toHit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (T row : rows) {
            if (out.size() >= limit) break;
            boolean hit = fields.apply(row).anyMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains(term));
            if (hit && visible.test(row)) out.add(toHit.apply(row));
        }
        return out;
    }

    private static Map<String, Object> hit(String type, Long id, String title, String subtitle, String href) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("title", title == null ? "(untitled)" : title);
        m.put("subtitle", subtitle);
        m.put("href", href);
        return m;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + " · " + b;
    }
}
