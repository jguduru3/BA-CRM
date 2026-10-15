package com.betteranalytics.crm.security;

import java.net.URI;
import java.util.Locale;

/**
 * Validation for user-entered URL fields that are later rendered as links
 * (company website, contact LinkedIn, custom-database URL properties, ...).
 * Only http, https and mailto are accepted — anything else (javascript:,
 * data:, vbscript:, file:, ...) is rejected so stored data can never become
 * an XSS vector, whatever the client does. A bare domain gets https:// added.
 */
public final class UrlFields {

    private UrlFields() {}

    /**
     * @return the normalised URL, or null for blank input
     * @throws IllegalArgumentException (→ 400) when the value is not a safe link
     */
    public static String normalize(String field, String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.length() > 1000) throw new IllegalArgumentException(field + " is too long");
        for (char c : v.toCharArray()) {
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException(field + " must not contain spaces or control characters");
            }
        }
        // No scheme ("acme.com/path", "acme.com:8080") — treat it as a web address.
        if (!v.toLowerCase(Locale.ROOT).matches("^[a-z][a-z0-9+-]*:.*")) v = "https://" + v;
        URI uri;
        try {
            uri = URI.create(v);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "http", "https" -> {
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new IllegalArgumentException(field + " must include a host");
                }
            }
            case "mailto" -> {
                if (uri.getSchemeSpecificPart() == null || !uri.getSchemeSpecificPart().contains("@")) {
                    throw new IllegalArgumentException(field + " must be a valid mailto: address");
                }
            }
            default -> throw new IllegalArgumentException(field + " must be an http(s) or mailto link");
        }
        return v;
    }

    /** True when the stored value is safe to render as a link. */
    public static boolean isSafe(String value) {
        try {
            return normalize("url", value) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
