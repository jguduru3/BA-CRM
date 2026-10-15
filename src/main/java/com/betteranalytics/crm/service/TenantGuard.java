package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.Company;
import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.Department;
import com.betteranalytics.crm.entity.Team;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Tenant-integrity checks for foreign keys supplied by the client.
 *
 * Every id a request body points at (owner, company, contact, category,
 * department, parent, related record...) must belong to the caller's
 * organization. Without this a user in tenant A could attach their records to
 * tenant B's rows simply by guessing ids, and the response/joins would then
 * leak tenant B's data back.
 *
 * Every helper treats a null id as "not supplied" and returns null — whether
 * the field is mandatory is the caller's decision. A non-null id that doesn't
 * resolve inside the caller's org throws IllegalArgumentException, which
 * GlobalExceptionHandler maps to 400 {"error": "..."}. We deliberately say
 * "not found in this organization" rather than distinguishing "exists
 * elsewhere" from "doesn't exist", so the message can't be used as an oracle.
 */
@Service
public class TenantGuard {

    /** Canonical related-record types used by activities/tasks (matches the frontend's RELATED_TYPES). */
    private static final Map<String, String> RELATED_TYPES = Map.of(
            "lead", "Lead",
            "opportunity", "Opportunity",
            "contact", "Contact",
            "company", "Company");

    private final CurrentUser                currentUser;
    private final UserRepository             userRepository;
    private final CompanyRepository          companyRepository;
    private final ContactRepository          contactRepository;
    private final ContactCategoryRepository  contactCategoryRepository;
    private final DepartmentRepository       departmentRepository;
    private final TeamRepository             teamRepository;
    private final LeadRepository             leadRepository;
    private final OpportunityRepository      opportunityRepository;
    private final ProjectRepository          projectRepository;

    public TenantGuard(CurrentUser currentUser,
                       UserRepository userRepository,
                       CompanyRepository companyRepository,
                       ContactRepository contactRepository,
                       ContactCategoryRepository contactCategoryRepository,
                       DepartmentRepository departmentRepository,
                       TeamRepository teamRepository,
                       LeadRepository leadRepository,
                       OpportunityRepository opportunityRepository,
                       ProjectRepository projectRepository) {
        this.currentUser               = currentUser;
        this.userRepository            = userRepository;
        this.companyRepository         = companyRepository;
        this.contactRepository         = contactRepository;
        this.contactCategoryRepository = contactCategoryRepository;
        this.departmentRepository      = departmentRepository;
        this.teamRepository            = teamRepository;
        this.leadRepository            = leadRepository;
        this.opportunityRepository     = opportunityRepository;
        this.projectRepository         = projectRepository;
    }

    private Long orgId() {
        return currentUser.organizationId();
    }

    private static IllegalArgumentException notFound(String what, Long id) {
        return new IllegalArgumentException(what + " " + id + " not found in this organization");
    }

    public User requireUser(Long id) {
        if (id == null) return null;
        return userRepository.findByIdAndOrganizationId(id, orgId()).orElseThrow(() -> notFound("user", id));
    }

    public Company requireCompany(Long id) {
        if (id == null) return null;
        return companyRepository.findByIdAndOrganizationId(id, orgId()).orElseThrow(() -> notFound("company", id));
    }

    public Contact requireContact(Long id) {
        if (id == null) return null;
        return contactRepository.findByIdAndOrganizationId(id, orgId()).orElseThrow(() -> notFound("contact", id));
    }

    public void requireContactCategory(Long id) {
        if (id == null) return;
        contactCategoryRepository.findByIdAndOrganizationId(id, orgId()).orElseThrow(() -> notFound("category", id));
    }

    public Department requireDepartment(Long id) {
        if (id == null) return null;
        return departmentRepository.findByIdAndOrganizationId(id, orgId()).orElseThrow(() -> notFound("department", id));
    }

    public Team requireTeam(Long id) {
        if (id == null) return null;
        return teamRepository.findByIdAndOrganizationId(id, orgId()).orElseThrow(() -> notFound("team", id));
    }

    public void requireProject(Long id) {
        if (id == null) return;
        projectRepository.findByIdAndOrganizationId(id, orgId()).orElseThrow(() -> notFound("project", id));
    }

    /**
     * Validates a polymorphic (relatedType, relatedId) link and returns the
     * canonical relatedType spelling ("Lead", "Opportunity", ...), or null when
     * no link was supplied. A blank relatedType counts as "not supplied" because
     * the frontend's select sends "" for "—".
     */
    public String requireRelated(String relatedType, Long relatedId) {
        boolean hasType = relatedType != null && !relatedType.isBlank();
        if (!hasType && relatedId == null) return null;
        if (!hasType) {
            throw new IllegalArgumentException("relatedType is required when relatedId is set");
        }
        String canonical = RELATED_TYPES.get(relatedType.trim().toLowerCase(Locale.ROOT));
        if (canonical == null) {
            throw new IllegalArgumentException("relatedType must be one of " + RELATED_TYPES.values());
        }
        if (relatedId == null) return canonical; // type-only tag, nothing to resolve
        boolean exists = switch (canonical) {
            case "Lead"        -> leadRepository.findByIdAndOrganizationId(relatedId, orgId()).isPresent();
            case "Opportunity" -> opportunityRepository.findByIdAndOrganizationId(relatedId, orgId()).isPresent();
            case "Contact"     -> contactRepository.findByIdAndOrganizationId(relatedId, orgId()).isPresent();
            case "Company"     -> companyRepository.findByIdAndOrganizationId(relatedId, orgId()).isPresent();
            default            -> false;
        };
        if (!exists) throw notFound(canonical.toLowerCase(Locale.ROOT), relatedId);
        return canonical;
    }

    /** Clamp for list endpoints' `limit` query param. */
    public static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 1000));
    }
}
