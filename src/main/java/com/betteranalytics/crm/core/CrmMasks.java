package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Activity;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Opportunity;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.PermissionService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Field masks for CRM responses and exports (step 4 of the permission order).
 * The rules themselves live in PermissionService (canViewLeadContactInfo,
 * canViewOpportunityValue, ...); this only applies them to a full copy of the
 * record, so new columns (version, closeReason, ...) survive masking.
 * Masking never touches the managed entity — always a detached copy.
 */
@Component
public class CrmMasks {

    public static final String MASK = "••••••••";

    private final PermissionService permissionService;

    public CrmMasks(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    public Lead lead(User actor, Lead lead) {
        if (lead == null || permissionService.canViewLeadContactInfo(actor, lead)) return lead;
        Lead c = new Lead();
        BeanUtils.copyProperties(lead, c);
        c.setEmail(lead.getEmail() == null ? null : MASK);
        c.setPhone(lead.getPhone() == null ? null : MASK);
        return c;
    }

    public List<Lead> leads(User actor, List<Lead> leads) {
        return leads.stream().map(l -> lead(actor, l)).toList();
    }

    public Opportunity opportunity(User actor, Opportunity opp) {
        if (opp == null) return null;
        boolean maskValue = !permissionService.canViewOpportunityValue(actor);
        boolean maskProbability = !permissionService.canViewOpportunityProbability(actor);
        boolean maskPo = !permissionService.canViewOpportunityPoStatus(actor);
        if (!maskValue && !maskProbability && !maskPo) return opp;
        Opportunity c = new Opportunity();
        BeanUtils.copyProperties(opp, c);
        if (maskValue) c.setValue(null);
        if (maskProbability) c.setProbability(null);
        if (maskPo) c.setPoStatus(null);
        return c;
    }

    /**
     * Inside Sales seeing a deal only because they sourced its lead: stage and
     * deal context only — no financials, dates, notes or close reason.
     */
    public Opportunity sourcedOnly(Opportunity opp) {
        Opportunity m = permissionService.maskSourcedOpportunityForInsideSales(opp);
        m.setOwnerId(opp.getOwnerId());
        m.setCompanyId(opp.getCompanyId());
        m.setCreatedAt(opp.getCreatedAt());
        m.setUpdatedAt(opp.getUpdatedAt());
        m.setValue(null);
        m.setProbability(null);
        return m;
    }

    public Activity activity(User actor, Activity a) {
        return permissionService.maskActivityForResponse(actor, a);
    }

    public List<Activity> activities(User actor, List<Activity> list) {
        return permissionService.maskActivitiesForResponse(actor, list);
    }

    /** True when a value is the placeholder this class writes — never store it back. */
    public static boolean isMask(String v) {
        return MASK.equals(v);
    }
}
