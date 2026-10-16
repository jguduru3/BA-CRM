package com.betteranalytics.crm.permission;

import com.betteranalytics.crm.entity.Role;

import java.util.HashMap;
import java.util.Map;

/**
 * The feature-permission matrix, step 2 of PermissionService's fixed check
 * order (tenant -> feature permission -> record scope -> field mask).
 *
 * This is a direct transcription of the PDF spec's Section 2 ("Feature
 * permissions") and Section 5 ("Opportunity fields") tables, scoped to LEAD
 * and OPPORTUNITY only — the two resources with real controllers wired to
 * PermissionService so far. Extending this to COMPANY/CONTACT/TASK/ACTIVITY
 * later means adding rows here AND wiring their controllers to call
 * PermissionService — neither alone does anything.
 *
 * Rows not covered by an explicit PDF cell (e.g. the PDF has no per-role
 * breakdown for Company/Contact/Task/Activity) simply aren't in this table.
 * lookup() below fails closed (Scope.NONE) for anything not listed, on
 * purpose — an unconfigured combination should deny, never silently allow.
 */
final class FeaturePermissionTable {

    private FeaturePermissionTable() {}

    private record Key(Role role, Action action, ResourceType resource) {}

    private static final Map<Key, Scope> RULES = new HashMap<>();

    private static void rule(Scope scope, Action action, ResourceType resource, Role... roles) {
        for (Role r : roles) RULES.put(new Key(r, action, resource), scope);
    }

    static {
        // ── LEAD — PDF Section 2 ──────────────────────────────────
        // "Create lead: Yes" for every sales-tree role; SUPPORT sits outside it.
        rule(Scope.ORG, Action.CREATE, ResourceType.LEAD,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);

        // "View / edit leads: Org / Org / Team / Own / Own / Own"
        rule(Scope.ORG, Action.VIEW, ResourceType.LEAD, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.VIEW, ResourceType.LEAD, Role.MANAGER);
        rule(Scope.OWN, Action.VIEW, ResourceType.LEAD, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        // SUPPORT — read-only, org-wide (README role table); writes are blocked centrally in lookup().
        rule(Scope.ORG, Action.VIEW, ResourceType.LEAD, Role.SUPPORT);

        rule(Scope.ORG, Action.EDIT, ResourceType.LEAD, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.EDIT, ResourceType.LEAD, Role.MANAGER);
        rule(Scope.OWN, Action.EDIT, ResourceType.LEAD, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);

        // "Assign / reassign leads: Org / Org / Within team / — / — / —"
        rule(Scope.ORG, Action.ASSIGN, ResourceType.LEAD, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.ASSIGN, ResourceType.LEAD, Role.MANAGER);

        // "Delete record (soft): Yes / Yes / Team, with reason / — / — / —"
        rule(Scope.ORG, Action.DELETE, ResourceType.LEAD, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.DELETE, ResourceType.LEAD, Role.MANAGER);
        // restore mirrors delete — only whoever could delete can put it back
        rule(Scope.ORG, Action.RESTORE, ResourceType.LEAD, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.RESTORE, ResourceType.LEAD, Role.MANAGER);

        // ── OPPORTUNITY — PDF Section 2 + Section 5 ───────────────
        // "Create / edit opportunity: Org / Org / Team / Own / — / Own"
        // NOTE: Inside Sales' PDF-specified "View (own sourced)" partial
        // visibility into opportunities converted from leads they sourced is
        // NOT implemented here — this table gives Inside Sales NONE for
        // Opportunity across the board, a safe simplification (sees nothing,
        // rather than guessing at "own sourced" semantics) until that's
        // explicitly asked for.
        rule(Scope.ORG, Action.CREATE, ResourceType.OPPORTUNITY, Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.OPPORTUNITY, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.VIEW, ResourceType.OPPORTUNITY, Role.MANAGER);
        rule(Scope.OWN, Action.VIEW, ResourceType.OPPORTUNITY, Role.EXECUTIVE, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.OPPORTUNITY, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.OPPORTUNITY, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.EDIT, ResourceType.OPPORTUNITY, Role.MANAGER);
        rule(Scope.OWN, Action.EDIT, ResourceType.OPPORTUNITY, Role.EXECUTIVE, Role.FIELD_SALES);

        // Section 2's Delete row is written for leads/general records; applying
        // the same Manager/VP/SuperAdmin-only pattern to Opportunity for consistency.
        rule(Scope.ORG, Action.DELETE, ResourceType.OPPORTUNITY, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.DELETE, ResourceType.OPPORTUNITY, Role.MANAGER);
        rule(Scope.ORG, Action.RESTORE, ResourceType.OPPORTUNITY, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.RESTORE, ResourceType.OPPORTUNITY, Role.MANAGER);

        // ── COMPANY — NOT in the PDF's role table. Default, not spec. ─
        // The PDF's Lead-fields table (Section 3) says Company is "Edit" for
        // EVERY role, with the note "Name + owner visible org-wide for
        // duplicate checks." Read literally, that makes Company org-wide
        // visible/editable for every sales-tree role — so unlike Lead/
        // Opportunity, there's no OWN/TEAM restriction here at all.
        rule(Scope.ORG, Action.CREATE, ResourceType.COMPANY,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.COMPANY,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        // SUPPORT (outside the sales tree, per the Step 1 default) — read-only.
        rule(Scope.ORG, Action.VIEW, ResourceType.COMPANY, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.COMPANY,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        // Delete wasn't given an explicit rule for Company either — applying
        // the same Manager+-only pattern used for every other soft-deletable
        // object, for consistency rather than because the PDF says so.
        rule(Scope.ORG, Action.DELETE, ResourceType.COMPANY, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.DELETE, ResourceType.COMPANY, Role.MANAGER);
        rule(Scope.ORG, Action.RESTORE, ResourceType.COMPANY, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.RESTORE, ResourceType.COMPANY, Role.MANAGER);

        // ── CONTACT — NOT in the PDF at all (the "Contact (name, phone,
        // email)" row in Section 3 is LEAD fields, not this separate Contact
        // object). Default: same OWN/TEAM/ORG shape as Lead, since a Contact
        // has an owner and behaves like a sales-touched record — this is a
        // judgment call, not a transcription.
        rule(Scope.ORG, Action.CREATE, ResourceType.CONTACT,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.CONTACT, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.VIEW, ResourceType.CONTACT, Role.MANAGER);
        rule(Scope.OWN, Action.VIEW, ResourceType.CONTACT, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        // SUPPORT — read-only, org-wide (Step 1 default).
        rule(Scope.ORG, Action.VIEW, ResourceType.CONTACT, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.CONTACT, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.EDIT, ResourceType.CONTACT, Role.MANAGER);
        rule(Scope.OWN, Action.EDIT, ResourceType.CONTACT, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.CONTACT, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.DELETE, ResourceType.CONTACT, Role.MANAGER);
        rule(Scope.ORG, Action.RESTORE, ResourceType.CONTACT, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.RESTORE, ResourceType.CONTACT, Role.MANAGER);

        // ── TASK — also not in the PDF. Default: same OWN/TEAM/ORG shape,
        // scoped by assigneeId instead of ownerId (handled in the controller,
        // not here — the table doesn't know field names, just scope).
        rule(Scope.ORG, Action.CREATE, ResourceType.TASK,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.TASK, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.VIEW, ResourceType.TASK, Role.MANAGER);
        rule(Scope.OWN, Action.VIEW, ResourceType.TASK, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.TASK, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.TASK, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.EDIT, ResourceType.TASK, Role.MANAGER);
        rule(Scope.OWN, Action.EDIT, ResourceType.TASK, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.TASK, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.DELETE, ResourceType.TASK, Role.MANAGER);
        rule(Scope.ORG, Action.RESTORE, ResourceType.TASK, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.RESTORE, ResourceType.TASK, Role.MANAGER);

        // ── ACTIVITY — Section 2: "Log activities: Yes / Yes / Yes / Own
        // records / Own records / Own records." That's a CREATE rule read as
        // a VIEW-equivalent scope for the timeline itself (own vs team vs
        // org). No EDIT/DELETE/RESTORE rules exist here on purpose — Section
        // 4 makes the timeline append-only for everyone, SuperAdmin included;
        // leaving those actions undefined means they fail closed already,
        // which is the correct behavior without writing a single line
        // enforcing it.
        rule(Scope.ORG, Action.CREATE, ResourceType.ACTIVITY,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.ACTIVITY, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.VIEW, ResourceType.ACTIVITY, Role.MANAGER);
        rule(Scope.OWN, Action.VIEW, ResourceType.ACTIVITY, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        // SUPPORT — read-only, org-wide (Step 1 default).
        rule(Scope.ORG, Action.VIEW, ResourceType.ACTIVITY, Role.SUPPORT);

        // ── DATABASE (custom databases) — not in the original PDF spec.
        // Treat like Company: org-wide visible/editable for all sales roles.
        // Only SUPER_ADMIN and VP_SALES can delete schemas.
        rule(Scope.ORG, Action.CREATE, ResourceType.DATABASE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.DATABASE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.DATABASE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.DATABASE, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.RESTORE, ResourceType.DATABASE, Role.SUPER_ADMIN, Role.VP_SALES);

        // ── DOCUMENT — same open access pattern as DATABASE.
        rule(Scope.ORG, Action.CREATE, ResourceType.DOCUMENT,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.DOCUMENT,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.DOCUMENT,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.DOCUMENT, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.RESTORE, ResourceType.DOCUMENT, Role.SUPER_ADMIN, Role.VP_SALES);

        // ── CAMPAIGN — marketing actions, SUPER_ADMIN + VP_SALES manage;
        // others can view only.
        rule(Scope.ORG, Action.CREATE, ResourceType.CAMPAIGN, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.CAMPAIGN,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.EDIT, ResourceType.CAMPAIGN, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.CAMPAIGN, Role.SUPER_ADMIN, Role.VP_SALES);

        // ── NOTIFICATION — every user can view/edit their own notifications.
        rule(Scope.OWN, Action.VIEW, ResourceType.NOTIFICATION,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.OWN, Action.EDIT, ResourceType.NOTIFICATION,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);

        // ── AI_JOB — any sales role can trigger and view AI jobs org-wide.
        rule(Scope.ORG, Action.CREATE, ResourceType.AI_JOB,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.AI_JOB,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);

        // ── ORG_SETTINGS — all authenticated users can view (to know if AI is on);
        // only SUPER_ADMIN can edit (enforced directly in OrgSettingsController).
        rule(Scope.ORG, Action.VIEW, ResourceType.ORG_SETTINGS,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.ORG_SETTINGS, Role.SUPER_ADMIN);

        // ── MEETING — anyone with a sales or support role can create/view meetings;
        // managers and above can view all; reps see own (organizer scope).
        rule(Scope.ORG, Action.CREATE, ResourceType.MEETING,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.VIEW, ResourceType.MEETING,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER);
        rule(Scope.OWN, Action.VIEW, ResourceType.MEETING,
                Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.MEETING,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER);
        rule(Scope.OWN, Action.EDIT, ResourceType.MEETING,
                Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.MEETING,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER);
        rule(Scope.OWN, Action.DELETE, ResourceType.MEETING,
                Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);

        // ── NOTE — all sales roles can create/view notes; edit/delete own notes.
        rule(Scope.ORG, Action.CREATE, ResourceType.NOTE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.VIEW, ResourceType.NOTE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.NOTE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER);
        rule(Scope.OWN, Action.EDIT, ResourceType.NOTE,
                Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.DELETE, ResourceType.NOTE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER);
        rule(Scope.OWN, Action.DELETE, ResourceType.NOTE,
                Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);

        // ── RECORD_PAGE — all roles can view; only those with edit on the parent record should edit.
        rule(Scope.ORG, Action.VIEW, ResourceType.RECORD_PAGE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.CREATE, ResourceType.RECORD_PAGE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);
        rule(Scope.ORG, Action.EDIT, ResourceType.RECORD_PAGE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES);

        // ── WORKSPACE — SUPER_ADMIN manages; all others can view.
        rule(Scope.ORG, Action.CREATE, ResourceType.WORKSPACE, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.VIEW, ResourceType.WORKSPACE,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.WORKSPACE, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.WORKSPACE, Role.SUPER_ADMIN);

        // ── DEPARTMENT — SUPER_ADMIN manages; managers and above can view.
        rule(Scope.ORG, Action.CREATE, ResourceType.DEPARTMENT, Role.SUPER_ADMIN);
        rule(Scope.ORG, Action.VIEW, ResourceType.DEPARTMENT,
                Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.DEPARTMENT, Role.SUPER_ADMIN);
        rule(Scope.ORG, Action.DELETE, ResourceType.DEPARTMENT, Role.SUPER_ADMIN);

        // ── Workspace tools (V13+). Not in the PDF; defaults chosen to match
        // what the navigation already implies. OWN = creator/owner of the row.
        Role[] sales = {Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES};
        Role[] reps = {Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES};
        Role[] managerUp = {Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER};
        Role[] everyone = {Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT};

        // PROJECT — anyone in sales can start one; owner (or Manager+) changes it.
        rule(Scope.ORG, Action.CREATE, ResourceType.PROJECT, sales);
        rule(Scope.ORG, Action.VIEW, ResourceType.PROJECT, everyone);
        rule(Scope.ORG, Action.EDIT, ResourceType.PROJECT, managerUp);
        rule(Scope.OWN, Action.EDIT, ResourceType.PROJECT, reps);
        rule(Scope.ORG, Action.DELETE, ResourceType.PROJECT, managerUp);
        rule(Scope.OWN, Action.DELETE, ResourceType.PROJECT, reps);

        // TODO lists are personal (sharing is handled by the controller on top of OWN).
        rule(Scope.OWN, Action.CREATE, ResourceType.TODO, sales);
        rule(Scope.OWN, Action.VIEW, ResourceType.TODO, everyone);
        rule(Scope.OWN, Action.EDIT, ResourceType.TODO, sales);
        rule(Scope.OWN, Action.DELETE, ResourceType.TODO, sales);

        // CALENDAR_EVENT — Manager+ see/manage everyone's calendar; others their own.
        rule(Scope.ORG, Action.CREATE, ResourceType.CALENDAR_EVENT, sales);
        rule(Scope.ORG, Action.VIEW, ResourceType.CALENDAR_EVENT, managerUp);
        rule(Scope.OWN, Action.VIEW, ResourceType.CALENDAR_EVENT, Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT);
        rule(Scope.ORG, Action.EDIT, ResourceType.CALENDAR_EVENT, managerUp);
        rule(Scope.OWN, Action.EDIT, ResourceType.CALENDAR_EVENT, reps);
        rule(Scope.ORG, Action.DELETE, ResourceType.CALENDAR_EVENT, managerUp);
        rule(Scope.OWN, Action.DELETE, ResourceType.CALENDAR_EVENT, reps);

        // REMINDER — strictly personal.
        rule(Scope.OWN, Action.CREATE, ResourceType.REMINDER, sales);
        rule(Scope.OWN, Action.VIEW, ResourceType.REMINDER, everyone);
        rule(Scope.OWN, Action.DELETE, ResourceType.REMINDER, sales);

        // CONTACT_CATEGORY — shared taxonomy, curated by Manager+.
        rule(Scope.ORG, Action.VIEW, ResourceType.CONTACT_CATEGORY, everyone);
        rule(Scope.ORG, Action.CREATE, ResourceType.CONTACT_CATEGORY, managerUp);
        rule(Scope.ORG, Action.EDIT, ResourceType.CONTACT_CATEGORY, managerUp);
        rule(Scope.ORG, Action.DELETE, ResourceType.CONTACT_CATEGORY, managerUp);

        // WHITEBOARD — collaborative: any sales role edits; creator or Manager+ deletes.
        rule(Scope.ORG, Action.CREATE, ResourceType.WHITEBOARD, sales);
        rule(Scope.ORG, Action.VIEW, ResourceType.WHITEBOARD, everyone);
        rule(Scope.ORG, Action.EDIT, ResourceType.WHITEBOARD, sales);
        rule(Scope.ORG, Action.DELETE, ResourceType.WHITEBOARD, managerUp);
        rule(Scope.OWN, Action.DELETE, ResourceType.WHITEBOARD, reps);

        // TEAM rosters drive TEAM-scope permissions, so only VP / Super Admin change them;
        // Manager+ can see them (matches the Teams menu item).
        rule(Scope.ORG, Action.VIEW, ResourceType.TEAM, managerUp);
        rule(Scope.ORG, Action.CREATE, ResourceType.TEAM, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.EDIT, ResourceType.TEAM, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.TEAM, Role.SUPER_ADMIN, Role.VP_SALES);

        // WHATSAPP — CREATE = send a message, VIEW = conversation metadata (bodies are a
        // field mask: PermissionService.canViewWhatsAppBody), EDIT/DELETE = sender accounts.
        rule(Scope.ORG, Action.CREATE, ResourceType.WHATSAPP, sales);
        rule(Scope.ORG, Action.VIEW, ResourceType.WHATSAPP, everyone);
        rule(Scope.ORG, Action.EDIT, ResourceType.WHATSAPP, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.WHATSAPP, Role.SUPER_ADMIN, Role.VP_SALES);

        // AI_AUTOMATION — Manager+; EMAIL_ACCOUNT holds credentials, VP / Super Admin only.
        rule(Scope.ORG, Action.VIEW, ResourceType.AI_AUTOMATION, managerUp);
        rule(Scope.ORG, Action.CREATE, ResourceType.AI_AUTOMATION, managerUp);
        rule(Scope.ORG, Action.EDIT, ResourceType.AI_AUTOMATION, managerUp);
        rule(Scope.ORG, Action.DELETE, ResourceType.AI_AUTOMATION, managerUp);
        rule(Scope.ORG, Action.VIEW, ResourceType.EMAIL_ACCOUNT, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.CREATE, ResourceType.EMAIL_ACCOUNT, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.ORG, Action.DELETE, ResourceType.EMAIL_ACCOUNT, Role.SUPER_ADMIN, Role.VP_SALES);

        // AUDIT_LOG — whole org for VP / Super Admin, own team (reporting tree) for Managers.
        rule(Scope.ORG, Action.VIEW, ResourceType.AUDIT_LOG, Role.SUPER_ADMIN, Role.VP_SALES);
        rule(Scope.TEAM, Action.VIEW, ResourceType.AUDIT_LOG, Role.MANAGER);

        // INTEGRATION — API keys / webhooks: Super Admin only.
        for (Action a : Action.values()) rule(Scope.ORG, a, ResourceType.INTEGRATION, Role.SUPER_ADMIN);
    }

    /**
     * Fails closed: any (role, action, resource) not explicitly listed above is Scope.NONE.
     *
     * SUPPORT is read-only everywhere — enforced here, centrally, so no rule above
     * can accidentally grant it a write. The one exception is its own notification
     * inbox (marking items read is personal state, not CRM data).
     */
    static Scope lookup(Role role, Action action, ResourceType resource) {
        if (role == Role.SUPPORT && action != Action.VIEW
                && !(resource == ResourceType.NOTIFICATION && action == Action.EDIT)) {
            return Scope.NONE;
        }
        return RULES.getOrDefault(new Key(role, action, resource), Scope.NONE);
    }
}
