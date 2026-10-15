package com.betteranalytics.crm.entity;

/**
 * Six-role sales hierarchy per the "CRM-Role_based_Features" spec, replacing
 * the old flat ADMIN/SALES_MANAGER/SALES_REP/SUPPORT set (V3 migration maps
 * old values onto these: ADMIN->SUPER_ADMIN, SALES_MANAGER->MANAGER,
 * SALES_REP->EXECUTIVE as a default; SUPPORT is unchanged and kept as a
 * non-sales, org-wide read-only role, sitting outside the hierarchy below).
 *
 * Reporting tree (see User.reportsToId for how this is walked):
 *   SUPER_ADMIN  — sits BESIDE the tree, not above it. Runs the system
 *                  (users/roles/config/integrations/audit), not the pipeline.
 *   VP_SALES     — top of the sales tree. Sees every team, every deal.
 *   MANAGER      — runs one (or more) team(s). reportsToId -> a VP_SALES user.
 *   EXECUTIVE    — converts qualified leads into closed deals.
 *   INSIDE_SALES — sources/qualifies leads by phone/email/LinkedIn/WhatsApp.
 *   FIELD_SALES  — in-person meetings and deals in a territory (User.territory).
 *   Executive, Inside Sales and Field Sales are flat peers under one Manager
 *   (reportsToId -> that MANAGER user), per the spec's own hierarchy diagram —
 *   there is no further nesting under them.
 *
 * NOTE: this enum + User.reportsToId is the data model only. The actual
 * enforcement (PermissionService: tenant -> feature permission -> record
 * scope -> field mask) is still open work — see the migration notes.
 */
public enum Role {
    SUPER_ADMIN,
    VP_SALES,
    MANAGER,
    EXECUTIVE,
    INSIDE_SALES,
    FIELD_SALES,
    SUPPORT
}
