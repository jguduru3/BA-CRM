package com.betteranalytics.crm.permission;

/**
 * The resources PermissionService currently knows rules for. Deliberately
 * NOT exhaustive against the full PDF matrix — Campaigns, WhatsApp/Email,
 * AI queries, Automation rules, Custom databases, Integrations, etc. have
 * no real backend yet, so there's nothing to enforce permissions on. Add a
 * resource here only when a controller actually exists to wire it into.
 *
 * LEAD and OPPORTUNITY are direct transcriptions of the PDF's Section 2/5
 * tables. COMPANY, CONTACT, and TASK have NO role-scope table in the PDF at
 * all — the rules for them in FeaturePermissionTable are reasonable
 * defaults inferred from context (see that class's comments for each),
 * not spec, and should be confirmed/adjusted rather than assumed correct.
 * ACTIVITY's create/view rule is spec-derived (Section 2's "Log activities"
 * row); its append-only nature (no EDIT/DELETE ever, per Section 4) is
 * already true by omission — no rule is defined for those actions, so they
 * fail closed.
 */
public enum ResourceType {
    LEAD,
    OPPORTUNITY,
    COMPANY,
    CONTACT,
    TASK,
    ACTIVITY,
    DATABASE,
    DOCUMENT,
    CAMPAIGN,
    NOTIFICATION,
    AI_JOB,
    ORG_SETTINGS,
    MEETING,
    NOTE,
    RECORD_PAGE,
    WORKSPACE,
    DEPARTMENT,
    CONTACT_CATEGORY,
    CALENDAR_EVENT,
    TODO,
    REMINDER,
    PROJECT,
    AI_AUTOMATION,
    EMAIL_ACCOUNT,
    WHITEBOARD,
    TEAM,
    WHATSAPP,
    AUDIT_LOG,
    INTEGRATION,
    SEQUENCE
}
