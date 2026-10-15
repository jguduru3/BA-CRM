package com.betteranalytics.crm.service;

import java.util.regex.Pattern;

/**
 * Canonical phone form used for every DB match in the engagement domain
 * (WhatsApp messages, campaign recipients, consent): E.164 with a leading '+',
 * e.g. "+919876543210". Columns holding this form are named normalized_phone
 * and are indexed.
 *
 * Leads / contacts (other domains) keep their free-form phone column; matching
 * against them strips the same formatting characters inside the query with
 * nested REPLACE() calls (see {@link #SQL_STRIP_FORMAT}) — portable across
 * PostgreSQL and H2, but not index-assisted. Adding a normalized column there
 * belongs to the owning domain.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    private static final Pattern FORMATTING = Pattern.compile("[\\s\\-().]");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /** JPQL/SQL template: wrap a column to strip the characters {@link #normalize} removes. */
    public static final String SQL_STRIP_FORMAT =
            "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(%s, ' ', ''), '-', ''), '(', ''), ')', ''), '.', '')";

    /** E.164 with '+', or IllegalArgumentException (→ 400). */
    public static String normalize(String raw) {
        String n = tryNormalize(raw);
        if (n == null) throw new IllegalArgumentException("phone must be an E.164 number, e.g. +919876543210");
        return n;
    }

    /** E.164 with '+', or null when the input is not a plausible international number. */
    public static String tryNormalize(String raw) {
        if (raw == null) return null;
        String p = FORMATTING.matcher(raw.trim()).replaceAll("");
        if (p.startsWith("00")) p = "+" + p.substring(2);
        if (!p.startsWith("+")) p = "+" + p;
        return E164.matcher(p).matches() ? p : null;
    }

    /** The digits-only form Meta's Graph API uses in webhooks ("919876543210"). */
    public static String digits(String normalized) {
        return normalized == null ? null : normalized.startsWith("+") ? normalized.substring(1) : normalized;
    }
}
