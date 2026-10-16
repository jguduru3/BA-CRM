package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.service.AiAutomationService;

import java.util.HashMap;
import java.util.Map;

/**
 * {{variable}} context for sequence steps. Substitution reuses the automation
 * template resolver ({@link AiAutomationService#resolve}); unknown variables
 * are left as-is.
 *
 * Variables: lead.* (id, name, email, phone, stage, source, industry,
 * requirement, businessName) for leads, contact.* (id, name, email, phone,
 * title) for contacts, and for both record.name / record.firstName /
 * record.email / record.phone, owner.name / owner.email.
 */
public final class SequenceTemplates {

    private SequenceTemplates() {}

    public static Map<String, String> forLead(Lead l, User owner) {
        Map<String, String> m = new HashMap<>();
        m.put("lead.id", String.valueOf(l.getId()));
        m.put("lead.name", nz(l.getName()));
        m.put("lead.email", nz(l.getEmail()));
        m.put("lead.phone", nz(l.getPhone()));
        m.put("lead.stage", nz(l.getStage()));
        m.put("lead.source", nz(l.getSource()));
        m.put("lead.industry", nz(l.getIndustry()));
        m.put("lead.requirement", nz(l.getRequirement()));
        m.put("lead.businessName", nz(l.getBusinessName()));
        common(m, l.getName(), l.getEmail(), l.getPhone(), owner);
        return m;
    }

    public static Map<String, String> forContact(Contact c, User owner) {
        Map<String, String> m = new HashMap<>();
        m.put("contact.id", String.valueOf(c.getId()));
        m.put("contact.name", nz(c.getName()));
        m.put("contact.email", nz(c.getEmail()));
        m.put("contact.phone", nz(c.getPhone()));
        m.put("contact.title", nz(c.getTitle()));
        common(m, c.getName(), c.getEmail(), c.getPhone(), owner);
        return m;
    }

    public static String render(String template, Map<String, String> vars) {
        return AiAutomationService.resolve(template, vars);
    }

    private static void common(Map<String, String> m, String name, String email, String phone, User owner) {
        m.put("record.name", nz(name));
        m.put("record.firstName", firstName(name));
        m.put("record.email", nz(email));
        m.put("record.phone", nz(phone));
        m.put("owner.name", owner != null ? nz(owner.getName()) : "");
        m.put("owner.email", owner != null ? nz(owner.getEmail()) : "");
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) return "";
        return name.trim().split("\\s+")[0];
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
