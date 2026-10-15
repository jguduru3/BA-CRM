package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.CompanyRepository;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.repository.OpportunityRepository;
import com.betteranalytics.crm.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Lead state transitions that must be safe under concurrency: claiming from the
 * unassigned pool and converting to an opportunity.
 */
@Service
public class LeadWorkflowService {

    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final PipelineSettingsService pipelineSettings;
    private final AuditService auditService;

    public LeadWorkflowService(LeadRepository leadRepository, OpportunityRepository opportunityRepository,
                               ContactRepository contactRepository, CompanyRepository companyRepository,
                               PipelineSettingsService pipelineSettings, AuditService auditService) {
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.pipelineSettings = pipelineSettings;
        this.auditService = auditService;
    }

    /**
     * Atomically takes an unowned lead: a single conditional UPDATE ... WHERE
     * owner_id IS NULL. Exactly one of several concurrent claimers gets
     * rows-updated == 1; everyone else gets 409 "already claimed".
     */
    @Transactional
    public Lead claim(User actor, Lead lead) {
        int updated = leadRepository.claimIfUnowned(lead.getId(), actor.getOrganizationId(), actor.getId(), Instant.now());
        if (updated != 1) throw new IllegalStateException("this lead has already been claimed by someone else");
        Lead claimed = leadRepository.findByIdAndOrganizationId(lead.getId(), actor.getOrganizationId())
                .orElseThrow(() -> new NoSuchElementException("lead not found"));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ownerId", actor.getId());
        after.put("claimedBy", actor.getName());
        auditService.log("claim", "leads", claimed.getId(), claimed.getName(), Map.of("ownerId", "unassigned"), after);
        return claimed;
    }

    public record Conversion(Lead lead, Opportunity opportunity, Contact contact, boolean created) {}

    /**
     * Converts a lead into an opportunity. Idempotent: the lead row is locked
     * (PESSIMISTIC_WRITE) for the whole transaction and opportunities.source_lead_id
     * is unique, so concurrent or repeated calls all return the same opportunity.
     *
     * The opportunity copies the lead's company (an existing Company, or one found /
     * created from the lead's business name), contact (a Contact in the org with the
     * lead's email, else a new one linked to the company), owner and value.
     */
    @Transactional
    public Conversion convert(User actor, Long leadId) {
        Long orgId = actor.getOrganizationId();
        Lead lead = leadRepository.lockByIdAndOrganizationId(leadId, orgId)
                .orElseThrow(() -> new NoSuchElementException("lead not found"));

        Opportunity existing = opportunityRepository.findBySourceLeadIdIncludingDeleted(lead.getId(), orgId).orElse(null);
        if (existing == null && lead.getConvertedOppId() != null) {
            existing = opportunityRepository.findByIdAndOrganizationIdIncludingDeleted(lead.getConvertedOppId(), orgId)
                    .orElse(null);
        }
        if (existing != null) {
            if (existing.getDeletedAt() != null) {
                throw new IllegalStateException("this lead was converted to opportunity #" + existing.getId()
                        + ", which is in the trash — restore it instead of converting again");
            }
            Contact contact = existing.getContactId() == null ? null
                    : contactRepository.findByIdAndOrganizationId(existing.getContactId(), orgId).orElse(null);
            return new Conversion(lead, existing, contact, false);
        }

        Instant now = Instant.now();
        Lead before = new Lead();
        org.springframework.beans.BeanUtils.copyProperties(lead, before);
        // The opportunity belongs to whoever owns the lead; a pool lead is first
        // assigned to the converting user so lead/opp/sourcedBy owners all agree.
        if (lead.getOwnerId() == null) lead.setOwnerId(actor.getId());

        Long companyId = lead.getCompanyId();
        if (companyId == null && lead.getBusinessName() != null && !lead.getBusinessName().isBlank()) {
            Company company = companyRepository
                    .findFirstByOrganizationIdAndNameIgnoreCaseOrderByIdAsc(orgId, lead.getBusinessName().trim())
                    .orElseGet(() -> createCompany(lead, now));
            companyId = company.getId();
            lead.setCompanyId(companyId);
        }

        Contact contact = null;
        if (lead.getEmail() != null && !lead.getEmail().isBlank()) {
            contact = contactRepository.findFirstByOrganizationIdAndEmailIgnoreCaseOrderByIdAsc(orgId, lead.getEmail().trim())
                    .orElse(null);
        }
        if (contact == null) contact = createContact(lead, companyId, now);

        Opportunity opp = new Opportunity();
        opp.setOrganizationId(orgId);
        opp.setName(lead.getName());
        opp.setCompanyId(companyId);
        opp.setContactId(contact.getId());
        opp.setOwnerId(lead.getOwnerId());
        opp.setStage(PipelineSettingsService.STAGES.get(0));
        opp.setValue(lead.getValue() != null ? lead.getValue() : 0L);
        opp.setProbability(pipelineSettings.get(orgId).probabilityFor(opp.getStage()));
        opp.setNotes(lead.getNotes());
        opp.setServiceProduct(lead.getRequirement());
        opp.setSourceLeadId(lead.getId());
        opp.setSourcedByUserId(lead.getOwnerId());
        opp.setCreatedAt(now);
        opp.setUpdatedAt(now);
        opportunityRepository.saveAndFlush(opp); // unique source_lead_id is the last line of defence

        lead.setStage("Won");
        lead.setConvertedAt(now);
        lead.setConvertedOppId(opp.getId());
        lead.setUpdatedAt(now);
        leadRepository.save(lead);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("converted", true);
        after.put("opportunityId", opp.getId());
        after.put("contactId", contact.getId());
        after.put("companyId", companyId);
        after.put("ownerId", lead.getOwnerId());
        auditService.log("update", "leads", lead.getId(), lead.getName(), before, after);
        auditService.log("create", "opportunities", opp.getId(), opp.getName(), null, opp);
        return new Conversion(lead, opp, contact, true);
    }

    private Company createCompany(Lead lead, Instant now) {
        Company c = new Company();
        c.setOrganizationId(lead.getOrganizationId());
        c.setName(lead.getBusinessName().trim());
        c.setIndustry(lead.getIndustry());
        c.setLocation(lead.getGeography());
        c.setOwnerId(lead.getOwnerId());
        c.setStatus("Prospect");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        companyRepository.save(c);
        auditService.log("create", "companies", c.getId(), c.getName(), null, c);
        return c;
    }

    private Contact createContact(Lead lead, Long companyId, Instant now) {
        Contact c = new Contact();
        c.setOrganizationId(lead.getOrganizationId());
        String name = lead.getDecisionMaker() != null && !lead.getDecisionMaker().isBlank()
                ? lead.getDecisionMaker() : lead.getName();
        c.setName(name.length() > 120 ? name.substring(0, 120) : name);
        c.setEmail(lead.getEmail());
        c.setPhone(lead.getPhone());
        c.setCompanyId(companyId);
        c.setOwnerId(lead.getOwnerId());
        c.setStatus("Active");
        c.setSource(lead.getSource());
        c.setIndustry(lead.getIndustry());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        contactRepository.save(c);
        auditService.log("create", "contacts", c.getId(), c.getName(), null, c);
        return c;
    }
}
