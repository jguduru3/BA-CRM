package com.betteranalytics.crm.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;

/**
 * A JSON request body read with "presence" semantics, for PATCH-style updates.
 *
 * Binding a body straight into an entity can't tell "the client didn't send
 * this field" from "the client sent null", and entity field defaults (value = 0,
 * stage = "Qualification", ...) silently overwrote stored data on every partial
 * save. Here only keys that are actually present are applied:
 *   - absent key           → field untouched
 *   - present, null / ""   → field cleared (optional fields) or ignored (required fields)
 *   - present, value       → field set (type-checked; bad types are a 400)
 */
public final class Patch {

    private final JsonNode node;

    private Patch(JsonNode node) {
        this.node = node;
    }

    public static Patch of(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return new Patch(new ObjectMapper().createObjectNode());
        if (!node.isObject()) throw new IllegalArgumentException("request body must be a JSON object");
        return new Patch(node);
    }

    public boolean has(String field) {
        return node.has(field);
    }

    /** Present with a non-null, non-blank value. */
    public boolean hasValue(String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() && !(v.isTextual() && v.asText().isBlank());
    }

    public String str(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isContainerNode()) throw new IllegalArgumentException(field + " must be a string");
        return v.asText();
    }

    public Long lng(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) return null;
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return Math.round(v.asDouble());
        if (v.isTextual()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(field + " must be a number");
            }
        }
        throw new IllegalArgumentException(field + " must be a number");
    }

    public Integer integer(String field) {
        Long l = lng(field);
        if (l == null) return null;
        if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) throw new IllegalArgumentException(field + " is out of range");
        return l.intValue();
    }

    public Boolean bool(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual() && ("true".equalsIgnoreCase(v.asText()) || "false".equalsIgnoreCase(v.asText()))) {
            return Boolean.parseBoolean(v.asText());
        }
        throw new IllegalArgumentException(field + " must be true or false");
    }

    public Instant instant(String field) {
        String s = str(field);
        if (s == null || s.isBlank()) return null;
        JsonNode v = node.get(field);
        if (v.isNumber()) return Instant.ofEpochMilli(v.asLong());
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e2) {
                try {
                    // a zone-less "2026-10-15T10:00" (datetime-local input) is read as UTC
                    if (s.contains("T")) return java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC);
                    // a bare date ("2026-10-15") from a date input → start of that day, UTC
                    return LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException e3) {
                    throw new IllegalArgumentException(field + " must be an ISO-8601 date-time");
                }
            }
        }
    }

    public LocalDate date(String field) {
        String s = str(field);
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be a date (YYYY-MM-DD)");
        }
    }

    // ── apply helpers: run the setter only when the key is present ──────────────

    /** Optional text: null or blank clears it. */
    public void text(String field, Consumer<String> setter) {
        if (!has(field)) return;
        String v = str(field);
        setter.accept(v == null || v.isBlank() ? null : v);
    }

    /** Required text (NOT NULL column): only a non-blank value is applied. */
    public void requiredText(String field, Consumer<String> setter) {
        if (hasValue(field)) setter.accept(str(field).trim());
    }

    public void longValue(String field, Consumer<Long> setter) {
        if (has(field)) setter.accept(lng(field));
    }

    public void intValue(String field, Consumer<Integer> setter) {
        if (has(field)) setter.accept(integer(field));
    }

    public void instantValue(String field, Consumer<Instant> setter) {
        if (has(field)) setter.accept(instant(field));
    }

    public void dateValue(String field, Consumer<LocalDate> setter) {
        if (has(field)) setter.accept(date(field));
    }
}
