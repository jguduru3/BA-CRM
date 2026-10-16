package com.betteranalytics.crm.dedupe;

import com.betteranalytics.crm.permission.ResourceType;

import java.util.Locale;

/** The record types duplicate management covers. */
public enum ObjectType {
    LEAD(ResourceType.LEAD, "Lead", "leads"),
    CONTACT(ResourceType.CONTACT, "Contact", "contacts"),
    COMPANY(ResourceType.COMPANY, "Company", "companies");

    public final ResourceType resource;
    /** The relatedType string child records (activities, notes, ...) use. */
    public final String relatedType;
    /** The audit resource / URL segment. */
    public final String plural;

    ObjectType(ResourceType resource, String relatedType, String plural) {
        this.resource = resource;
        this.relatedType = relatedType;
        this.plural = plural;
    }

    /** "lead", "LEAD", "leads" → LEAD; anything else → 400. */
    public static ObjectType parse(String raw) {
        if (raw != null) {
            String s = raw.trim().toUpperCase(Locale.ROOT);
            for (ObjectType t : values()) {
                if (t.name().equals(s) || t.plural.toUpperCase(Locale.ROOT).equals(s)) return t;
            }
        }
        throw new IllegalArgumentException("type must be one of lead, contact, company");
    }
}
