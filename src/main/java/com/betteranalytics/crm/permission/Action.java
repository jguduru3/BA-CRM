package com.betteranalytics.crm.permission;

/** What's being attempted, checked against the feature-permission matrix. */
public enum Action {
    VIEW,
    CREATE,
    EDIT,
    DELETE,
    RESTORE,
    ASSIGN   // reassigning a record's owner — distinct from a normal EDIT
             // because the spec gates it separately (e.g. Lead.ownerId is
             // read-only for individual contributors even though they can
             // EDIT other fields on their own leads)
}
