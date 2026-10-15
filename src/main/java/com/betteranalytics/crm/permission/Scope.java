package com.betteranalytics.crm.permission;

/**
 * How far a role's access reaches for a given (role, action, resource)
 * combination. This is the THIRD step in the fixed check order (tenant ->
 * feature permission -> record scope -> field mask) — a role's scope is
 * looked up first as a feature permission, then actually applied against
 * a specific record's ownership.
 */
public enum Scope {
    NONE,  // no access at all — feature permission denies this outright
    OWN,   // only records this exact user owns
    TEAM,  // own records + everyone "under" this user in the sales tree
    ORG    // every record in the organization
}
