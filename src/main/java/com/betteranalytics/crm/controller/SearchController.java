package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.ScopeSpecs;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.function.Function;

/**
 * Global search behind the ⌘K command palette: one query per object type across
 * leads, opportunities, companies, contacts and tasks. Matching, record scope and
 * the per-type limit are all applied in SQL (the same scope predicates as the
 * list screens), so search never reveals a record the caller couldn't open — and
 * only display fields (title / subtitle) are returned, never masked contact data.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final TaskRepository taskRepository;
    private final ScopeSpecs scopes;
    private final CurrentUser currentUser;

    public SearchController(LeadRepository leadRepository, OpportunityRepository opportunityRepository,
                            CompanyRepository companyRepository, ContactRepository contactRepository,
                            TaskRepository taskRepository, ScopeSpecs scopes, CurrentUser currentUser) {
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.taskRepository = taskRepository;
        this.scopes = scopes;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "6") int limit) {
        String term = q.trim();
        int per = Math.max(1, Math.min(limit, 20));
        List<Map<String, Object>> results = new ArrayList<>();
        if (term.length() < 2 || term.length() > 100) {
            return Map.of("query", q, "results", results);
        }
        User actor = currentUser.get();
        boolean insideSales = actor.getRole() == Role.INSIDE_SALES;

        // Inside Sales see handed-off leads with masked contact details, so email/phone
        // are not searchable for them (search would otherwise be an oracle).
        String[] leadFields = insideSales ? new String[]{"name", "businessName"}
                                          : new String[]{"name", "businessName", "email", "phone"};
        results.addAll(find(leadRepository, scopes.leads(actor), Specs.search(term, leadFields), per,
                l -> hit("lead", l.getId(), l.getName(), join(l.getBusinessName(), l.getStage()), "/leads?open=" + l.getId())));

        results.addAll(find(opportunityRepository, scopes.opportunities(actor), Specs.search(term, "name", "serviceProduct"), per,
                o -> hit("opportunity", o.getId(), o.getName(), o.getStage(), "/opportunities?open=" + o.getId())));

        results.addAll(find(companyRepository, scopes.companies(actor), Specs.search(term, "name", "industry", "website", "email"), per,
                c -> hit("company", c.getId(), c.getName(), join(c.getIndustry(), c.getLocation()), "/companies?open=" + c.getId())));

        results.addAll(find(contactRepository, scopes.contacts(actor), Specs.search(term, "name", "email", "phone", "title"), per,
                c -> hit("contact", c.getId(), c.getName(), join(c.getTitle(), c.getEmail()), "/contacts?open=" + c.getId())));

        results.addAll(find(taskRepository, scopes.tasks(actor), Specs.search(term, "title"), per,
                t -> hit("task", t.getId(), t.getTitle(), t.getStatus(), "/tasks?open=" + t.getId())));

        return Map.of("query", q, "results", results);
    }

    private static <T> List<Map<String, Object>> find(JpaSpecificationExecutor<T> repo, Specification<T> scope,
                                                      Specification<T> match, int limit,
                                                      Function<T, Map<String, Object>> toHit) {
        if (scope == null) return List.of(); // role can't view this type at all
        return repo.findAll(scope.and(match), PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt")))
                .getContent().stream().map(toHit).toList();
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
