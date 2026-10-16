package com.betteranalytics.crm.dedupe;

import java.util.Locale;

/** What a duplicate rule does when the matching rule finds candidates. */
public enum DuplicateAction {
    /** Save silently (matches still show up on the Duplicates page). */
    ALLOW,
    /** 409 DUPLICATE_WARNING with the candidates; the caller may resend with allowDuplicate=true. */
    WARN,
    /** 409 DUPLICATE_BLOCKED; no bypass. */
    BLOCK;

    public static DuplicateAction parse(String raw, String field) {
        if (raw != null) {
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the 400 below
            }
        }
        throw new IllegalArgumentException(field + " must be one of ALLOW, WARN, BLOCK");
    }
}
