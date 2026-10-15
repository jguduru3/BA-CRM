package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Company;
import com.betteranalytics.crm.repository.CompanyRepository;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.repository.OpportunityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Soft-deleting a company that still has live children. Without cascade the
 * caller gets a 409 listing what's still attached; with cascade the children are
 * soft-deleted in the same transaction, tagged so restoring the company brings
 * exactly those children back.
 */
@Service
public class CompanyDeletionService {

    private final CompanyRepository companyRepository;
    private final ContactRepository contactRepository;
    private final OpportunityRepository opportunityRepository;
    private final LeadRepository leadRepository;

    public CompanyDeletionService(CompanyRepository companyRepository, ContactRepository contactRepository,
                                  OpportunityRepository opportunityRepository, LeadRepository leadRepository) {
        this.companyRepository = companyRepository;
        this.contactRepository = contactRepository;
        this.opportunityRepository = opportunityRepository;
        this.leadRepository = leadRepository;
    }

    /** Non-deleted children, in display order: contacts, opportunities, leads. */
    public Map<String, Long> childCounts(Company company) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("contacts", contactRepository.countByOrganizationIdAndCompanyId(company.getOrganizationId(), company.getId()));
        counts.put("opportunities", opportunityRepository.countByOrganizationIdAndCompanyId(company.getOrganizationId(), company.getId()));
        counts.put("leads", leadRepository.countByOrganizationIdAndCompanyId(company.getOrganizationId(), company.getId()));
        return counts;
    }

    public static String describe(Map<String, Long> counts) {
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> {
            if (v > 0) parts.add(v + " " + (v == 1 ? k.substring(0, k.length() - (k.endsWith("ies") ? 3 : 1)) + (k.endsWith("ies") ? "y" : "") : k));
        });
        return String.join(", ", parts);
    }

    public static String cascadeReason(Long companyId) {
        return "Cascade: company #" + companyId + " deleted";
    }

    @Transactional
    public Map<String, Integer> softDelete(Company company, Long actorId, String reason, boolean cascade) {
        Instant now = Instant.now();
        Map<String, Integer> removed = new LinkedHashMap<>();
        if (cascade) {
            String tag = cascadeReason(company.getId());
            removed.put("contacts", contactRepository.softDeleteByCompany(company.getOrganizationId(), company.getId(), now, actorId, tag));
            removed.put("opportunities", opportunityRepository.softDeleteByCompany(company.getOrganizationId(), company.getId(), now, actorId, tag));
            removed.put("leads", leadRepository.softDeleteByCompany(company.getOrganizationId(), company.getId(), now, actorId, tag));
        }
        Company managed = companyRepository.findById(company.getId()).orElse(company);
        managed.setDeletedAt(now);
        managed.setDeletedBy(actorId);
        managed.setDeleteReason(reason);
        companyRepository.save(managed);
        return removed;
    }

    @Transactional
    public Map<String, Integer> restoreCascaded(Company company) {
        String tag = cascadeReason(company.getId());
        Map<String, Integer> restored = new LinkedHashMap<>();
        restored.put("contacts", contactRepository.restoreByCompany(company.getOrganizationId(), company.getId(), tag));
        restored.put("opportunities", opportunityRepository.restoreByCompany(company.getOrganizationId(), company.getId(), tag));
        restored.put("leads", leadRepository.restoreByCompany(company.getOrganizationId(), company.getId(), tag));
        return restored;
    }
}
