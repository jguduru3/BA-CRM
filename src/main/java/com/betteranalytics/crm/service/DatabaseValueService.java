package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.DatabaseProperty;
import com.betteranalytics.crm.entity.DatabaseRecord;
import com.betteranalytics.crm.entity.PropertyValue;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.UrlFields;
import com.betteranalytics.crm.service.formula.Formula;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Typed values for custom-database records: validation per property type,
 * relation/file target checks, server-side formula evaluation, and
 * relation-chip titles — all batched so a page of records costs a fixed
 * number of queries, never one per record.
 *
 * Canonical storage per type (other columns are cleared):
 *   text, url, email, phone, select → textValue
 *   number → numberValue · checkbox → boolValue · date → dateValue
 *   multi_select (option ids/labels), relation (record ids), file (attachment ids) → jsonValue (JSON array)
 *   formula → nothing stored; computed on read (read-only)
 */
@Service
public class DatabaseValueService {

    public static final Set<String> PROPERTY_TYPES = Set.of(
            "text", "number", "date", "select", "multi_select", "checkbox",
            "url", "email", "phone", "file", "relation", "formula");

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\-.\\s]{3,40}$");
    private static final int MAX_TEXT = 10_000;
    private static final int MAX_LINKS = 100;

    private final DatabasePropertyRepository propRepo;
    private final DatabaseRecordRepository recRepo;
    private final PropertyValueRepository valRepo;
    private final CustomDatabaseRepository dbRepo;
    private final AttachmentRepository attachmentRepo;
    private final ObjectMapper objectMapper;

    public DatabaseValueService(DatabasePropertyRepository propRepo, DatabaseRecordRepository recRepo,
                                PropertyValueRepository valRepo, CustomDatabaseRepository dbRepo,
                                AttachmentRepository attachmentRepo, ObjectMapper objectMapper) {
        this.propRepo = propRepo;
        this.recRepo = recRepo;
        this.valRepo = valRepo;
        this.dbRepo = dbRepo;
        this.attachmentRepo = attachmentRepo;
        this.objectMapper = objectMapper;
    }

    // ── Property definitions ──────────────────────────────────────────────────

    /** Validates a (new or changed) property definition; throws IllegalArgumentException (400). */
    public void validateDefinition(DatabaseProperty p, Long orgId) {
        if (p.getType() == null || !PROPERTY_TYPES.contains(p.getType())) {
            throw new IllegalArgumentException("unsupported property type");
        }
        switch (p.getType()) {
            case "relation" -> {
                if (p.getRelationDbId() == null) throw new IllegalArgumentException("a relation property needs relationDbId");
                if (dbRepo.findByIdAndOrganizationId(p.getRelationDbId(), orgId).isEmpty()) {
                    throw new IllegalArgumentException("related database not found");
                }
            }
            case "formula" -> Formula.parse(formulaExpression(p)); // FormulaSyntaxException → 400
            case "select", "multi_select" -> options(p); // must be a JSON array when present
            default -> { }
        }
    }

    /** A formula property's expression: optionsJson {"expression": "..."} (a bare string is accepted too). */
    public String formulaExpression(DatabaseProperty p) {
        String raw = p.getOptionsJson();
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("a formula property needs an expression");
        String t = raw.trim();
        if (!t.startsWith("{\"")) return t; // a bare expression such as {Price} * 2
        JsonNode n = readJson(t);
        if (n != null && n.isObject() && n.path("expression").isTextual()) return n.path("expression").asText();
        throw new IllegalArgumentException("formula optionsJson must be {\"expression\": \"...\"}");
    }

    /** Select options as id → label (ids and labels are both accepted as values). */
    private Map<String, String> options(DatabaseProperty p) {
        Map<String, String> out = new LinkedHashMap<>();
        if (p.getOptionsJson() == null || p.getOptionsJson().isBlank()) return out;
        JsonNode n = readJson(p.getOptionsJson());
        if (n == null || !n.isArray()) throw new IllegalArgumentException("optionsJson must be a JSON array of options");
        for (JsonNode o : n) {
            if (o.isTextual()) out.put(o.asText(), o.asText());
            else if (o.isObject()) {
                String label = o.path("label").asText(o.path("id").asText(""));
                out.put(o.path("id").asText(label), label);
            }
        }
        return out;
    }

    // ── Writing values ────────────────────────────────────────────────────────

    /**
     * Applies {"values":[{propertyId, textValue?, numberValue?, boolValue?, dateValue?, jsonValue?}]}
     * to a record. Every propertyId must belong to the record's database; each
     * value is validated for its property's type (400 otherwise). Existing values
     * are loaded with one query. On create, required properties must be filled.
     */
    public void applyValues(DatabaseRecord rec, Object valuesObj, Long orgId, boolean creating) {
        List<DatabaseProperty> props = propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(rec.getDatabaseId(), orgId);
        Map<Long, DatabaseProperty> byId = props.stream().collect(Collectors.toMap(DatabaseProperty::getId, p -> p));
        Map<Long, PropertyValue> existing = valRepo.findByRecordIdAndOrganizationId(rec.getId(), orgId).stream()
                .collect(Collectors.toMap(PropertyValue::getPropertyId, v -> v, (a, b) -> a));
        List<PropertyValue> toSave = new ArrayList<>();
        if (valuesObj != null) {
            if (!(valuesObj instanceof List<?> list)) throw new IllegalArgumentException("values must be a list");
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> v)) throw new IllegalArgumentException("each value must be an object");
                if (!(v.get("propertyId") instanceof Number pidNum)) throw new IllegalArgumentException("propertyId must be a number");
                DatabaseProperty prop = byId.get(pidNum.longValue());
                if (prop == null) {
                    throw new IllegalArgumentException("property " + pidNum.longValue() + " does not belong to this database");
                }
                PropertyValue pv = existing.computeIfAbsent(prop.getId(), id -> {
                    PropertyValue n = new PropertyValue();
                    n.setRecordId(rec.getId());
                    n.setPropertyId(id);
                    n.setOrganizationId(orgId);
                    return n;
                });
                writeTyped(prop, pv, v, orgId);
                toSave.add(pv);
            }
        }
        if (creating) {
            for (DatabaseProperty p : props) {
                if (!p.isRequired() || "formula".equals(p.getType())) continue;
                PropertyValue pv = existing.get(p.getId());
                if (pv == null || isEmpty(pv)) throw new IllegalArgumentException(p.getName() + " is required");
            }
        }
        valRepo.saveAll(toSave);
    }

    private void writeTyped(DatabaseProperty prop, PropertyValue pv, Map<?, ?> v, Long orgId) {
        String type = prop.getType();
        String name = prop.getName();
        if ("formula".equals(type)) throw new IllegalArgumentException(name + " is a formula and is computed automatically");
        // Clear every column, then set the canonical one: one value, one place.
        pv.setTextValue(null);
        pv.setNumberValue(null);
        pv.setBoolValue(null);
        pv.setDateValue(null);
        pv.setJsonValue(null);
        switch (type) {
            case "text" -> pv.setTextValue(text(v.get("textValue"), name));
            case "number" -> pv.setNumberValue(number(v.containsKey("numberValue") ? v.get("numberValue") : v.get("textValue"), name));
            case "checkbox" -> {
                Object b = v.get("boolValue");
                if (b != null && !(b instanceof Boolean)) throw new IllegalArgumentException(name + " must be true or false");
                pv.setBoolValue(b == null ? Boolean.FALSE : (Boolean) b);
            }
            case "date" -> pv.setDateValue(date(v.containsKey("dateValue") ? v.get("dateValue") : v.get("textValue"), name));
            case "url" -> pv.setTextValue(UrlFields.normalize(name, text(v.get("textValue"), name)));
            case "email" -> {
                String s = blankToNull(text(v.get("textValue"), name));
                if (s != null && (s.length() > 320 || !EMAIL.matcher(s).matches())) {
                    throw new IllegalArgumentException(name + " must be a valid email address");
                }
                pv.setTextValue(s);
            }
            case "phone" -> {
                String s = blankToNull(text(v.get("textValue"), name));
                if (s != null && (!PHONE.matcher(s).matches() || s.chars().noneMatch(Character::isDigit))) {
                    throw new IllegalArgumentException(name + " must be a valid phone number");
                }
                pv.setTextValue(s);
            }
            case "select" -> {
                String s = blankToNull(text(v.get("textValue"), name));
                Map<String, String> opts = options(prop);
                if (s != null && !opts.isEmpty() && !opts.containsKey(s) && !opts.containsValue(s)) {
                    throw new IllegalArgumentException(name + ": '" + s + "' is not one of its options");
                }
                pv.setTextValue(s);
            }
            case "multi_select" -> {
                List<String> items = stringArray(v.get("jsonValue"), name);
                Map<String, String> opts = options(prop);
                for (String s : items) {
                    if (!opts.isEmpty() && !opts.containsKey(s) && !opts.containsValue(s)) {
                        throw new IllegalArgumentException(name + ": '" + s + "' is not one of its options");
                    }
                }
                pv.setJsonValue(items.isEmpty() ? null : toJson(items));
            }
            case "relation" -> {
                List<Long> ids = idArray(v.get("jsonValue"), name);
                if (!ids.isEmpty()) {
                    if (prop.getRelationDbId() == null) throw new IllegalArgumentException(name + " has no related database");
                    long found = recRepo.findByIdInAndOrganizationId(ids, orgId).stream()
                            .filter(r -> prop.getRelationDbId().equals(r.getDatabaseId())).count();
                    if (found != ids.size()) {
                        throw new IllegalArgumentException(name + " can only link records of its related database");
                    }
                }
                pv.setJsonValue(ids.isEmpty() ? null : toJson(ids));
            }
            case "file" -> {
                List<Long> ids = idArray(v.get("jsonValue"), name);
                if (!ids.isEmpty() && attachmentRepo.findByIdInAndOrganizationId(ids, orgId).size() != ids.size()) {
                    throw new IllegalArgumentException(name + " references a file that does not exist");
                }
                pv.setJsonValue(ids.isEmpty() ? null : toJson(ids));
            }
            default -> throw new IllegalArgumentException("unsupported property type");
        }
    }

    // ── Reading records ───────────────────────────────────────────────────────

    /**
     * Builds the response envelopes for a page of records:
     * {record, values, formulas: {propId: {value, error}}, relations: {recordId: title}}.
     * Fixed query count: values for the page (1), and for relation chips the
     * linked records, their databases' title properties and title values (3).
     */
    public List<Map<String, Object>> envelopes(List<DatabaseRecord> records, List<DatabaseProperty> props, Long orgId) {
        if (records.isEmpty()) return List.of();
        Map<Long, List<PropertyValue>> valuesByRecord = valRepo
                .findByRecordIdInAndOrganizationId(records.stream().map(DatabaseRecord::getId).toList(), orgId)
                .stream().collect(Collectors.groupingBy(PropertyValue::getRecordId));
        Map<Long, String> titles = relationTitles(valuesByRecord.values(), props, orgId);
        List<Map<String, Object>> out = new ArrayList<>(records.size());
        for (DatabaseRecord rec : records) {
            List<PropertyValue> values = valuesByRecord.getOrDefault(rec.getId(), List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("record", rec);
            m.put("values", values);
            m.put("formulas", formulas(props, values, titles));
            Map<String, String> linked = new LinkedHashMap<>();
            for (PropertyValue v : values) {
                DatabaseProperty p = props.stream().filter(x -> x.getId().equals(v.getPropertyId())).findFirst().orElse(null);
                if (p == null || !"relation".equals(p.getType())) continue;
                for (Long id : safeIdArray(v.getJsonValue())) {
                    if (titles.containsKey(id)) linked.put(String.valueOf(id), titles.get(id));
                }
            }
            m.put("relations", linked);
            out.add(m);
        }
        return out;
    }

    /** Computes every formula property of one record; errors become {"error": code}. */
    Map<String, Object> formulas(List<DatabaseProperty> props, List<PropertyValue> values, Map<Long, String> titles) {
        List<DatabaseProperty> formulaProps = props.stream().filter(p -> "formula".equals(p.getType())).toList();
        if (formulaProps.isEmpty()) return Map.of();
        Map<String, DatabaseProperty> byName = new HashMap<>();
        for (DatabaseProperty p : props) byName.putIfAbsent(p.getName().trim().toLowerCase(Locale.ROOT), p);
        Map<Long, PropertyValue> valueByProp = values.stream()
                .collect(Collectors.toMap(PropertyValue::getPropertyId, v -> v, (a, b) -> a));
        Evaluator ev = new Evaluator(byName, valueByProp, titles);
        Map<String, Object> out = new LinkedHashMap<>();
        for (DatabaseProperty p : formulaProps) {
            Formula.Result r = ev.evaluate(p, 0);
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("value", r.value());
            cell.put("error", r.error());
            out.put(String.valueOf(p.getId()), cell);
        }
        return out;
    }

    /** Per-record formula evaluation with memoisation and cycle detection. */
    private final class Evaluator {
        private final Map<String, DatabaseProperty> byName;
        private final Map<Long, PropertyValue> values;
        private final Map<Long, String> titles;
        private final Map<Long, Formula.Result> done = new HashMap<>();
        private final Set<Long> visiting = new HashSet<>();

        Evaluator(Map<String, DatabaseProperty> byName, Map<Long, PropertyValue> values, Map<Long, String> titles) {
            this.byName = byName;
            this.values = values;
            this.titles = titles;
        }

        Formula.Result evaluate(DatabaseProperty p, int depth) {
            Formula.Result cached = done.get(p.getId());
            if (cached != null) return cached;
            if (!visiting.add(p.getId()) || depth > 10) return new Formula.Result(null, Formula.CYCLE);
            Formula.Result r;
            try {
                Formula f = Formula.parse(formulaExpression(p));
                r = f.evaluate(name -> field(name, depth));
            } catch (IllegalArgumentException bad) {
                r = new Formula.Result(null, "#ERROR!");
            }
            visiting.remove(p.getId());
            done.put(p.getId(), r);
            return r;
        }

        private Object field(String name, int depth) {
            DatabaseProperty p = byName.get(name.trim().toLowerCase(Locale.ROOT));
            if (p == null) throw new Formula.EvalError(Formula.REF);
            if ("formula".equals(p.getType())) return evaluate(p, depth + 1);
            PropertyValue v = values.get(p.getId());
            if (v == null) return "checkbox".equals(p.getType()) ? Boolean.FALSE : null;
            return switch (p.getType()) {
                case "number" -> v.getNumberValue();
                case "checkbox" -> Boolean.TRUE.equals(v.getBoolValue());
                case "date" -> v.getDateValue() == null ? null : v.getDateValue().toString().substring(0, 10);
                case "select" -> {
                    String s = v.getTextValue();
                    yield s == null ? null : options(p).getOrDefault(s, s);
                }
                case "multi_select" -> {
                    Map<String, String> opts = options(p);
                    yield safeStringArray(v.getJsonValue()).stream().map(s -> opts.getOrDefault(s, s)).collect(Collectors.joining(", "));
                }
                case "relation" -> safeIdArray(v.getJsonValue()).stream()
                        .map(id -> titles.getOrDefault(id, "#" + id)).collect(Collectors.joining(", "));
                case "file" -> BigDecimal.valueOf(safeIdArray(v.getJsonValue()).size());
                default -> v.getTextValue();
            };
        }
    }

    /** Titles (first text property, else "Untitled") of every record linked from these values. */
    private Map<Long, String> relationTitles(Collection<List<PropertyValue>> valueLists, List<DatabaseProperty> props, Long orgId) {
        Set<Long> relationProps = props.stream().filter(p -> "relation".equals(p.getType()) && p.getRelationDbId() != null)
                .map(DatabaseProperty::getId).collect(Collectors.toSet());
        if (relationProps.isEmpty()) return Map.of();
        Set<Long> ids = new LinkedHashSet<>();
        for (List<PropertyValue> list : valueLists) {
            for (PropertyValue v : list) {
                if (relationProps.contains(v.getPropertyId())) ids.addAll(safeIdArray(v.getJsonValue()));
            }
        }
        return titlesOf(ids, orgId);
    }

    /** Record id → display title, in three queries whatever the number of ids. */
    public Map<Long, String> titlesOf(Collection<Long> recordIds, Long orgId) {
        if (recordIds.isEmpty()) return Map.of();
        List<DatabaseRecord> linked = recRepo.findByIdInAndOrganizationId(recordIds, orgId);
        if (linked.isEmpty()) return Map.of();
        Set<Long> dbIds = linked.stream().map(DatabaseRecord::getDatabaseId).collect(Collectors.toSet());
        Map<Long, Long> titlePropByDb = new HashMap<>();
        for (DatabaseProperty p : propRepo.findByDatabaseIdInAndOrganizationIdOrderByPosition(dbIds, orgId)) {
            if ("text".equals(p.getType())) titlePropByDb.putIfAbsent(p.getDatabaseId(), p.getId());
        }
        Map<Long, String> titleValue = new HashMap<>();
        if (!titlePropByDb.isEmpty()) {
            Set<Long> titleProps = new HashSet<>(titlePropByDb.values());
            for (PropertyValue v : valRepo.findByRecordIdInAndOrganizationId(linked.stream().map(DatabaseRecord::getId).toList(), orgId)) {
                if (titleProps.contains(v.getPropertyId()) && v.getTextValue() != null) titleValue.put(v.getRecordId(), v.getTextValue());
            }
        }
        Map<Long, String> out = new LinkedHashMap<>();
        for (DatabaseRecord r : linked) {
            String t = titleValue.get(r.getId());
            out.put(r.getId(), t == null || t.isBlank() ? "Untitled #" + r.getId() : t);
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isEmpty(PropertyValue v) {
        return (v.getTextValue() == null || v.getTextValue().isBlank()) && v.getNumberValue() == null
                && v.getDateValue() == null && v.getBoolValue() == null
                && (v.getJsonValue() == null || v.getJsonValue().equals("[]"));
    }

    private static String text(Object o, String name) {
        if (o == null) return null;
        if (!(o instanceof String) && !(o instanceof Number) && !(o instanceof Boolean)) {
            throw new IllegalArgumentException(name + " must be text");
        }
        String s = String.valueOf(o);
        if (s.length() > MAX_TEXT) throw new IllegalArgumentException(name + " is too long");
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static BigDecimal number(Object o, String name) {
        if (o == null || (o instanceof String s && s.isBlank())) return null;
        try {
            if (o instanceof Number || o instanceof String) return new BigDecimal(o.toString().trim());
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new IllegalArgumentException(name + " must be a number");
    }

    private static Instant date(Object o, String name) {
        if (o == null || (o instanceof String s && s.isBlank())) return null;
        if (!(o instanceof String s)) throw new IllegalArgumentException(name + " must be an ISO date");
        try {
            return s.length() <= 10 ? LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be an ISO date (YYYY-MM-DD) or instant");
        }
    }

    private List<String> stringArray(Object o, String name) {
        JsonNode n = arrayNode(o, name);
        List<String> out = new ArrayList<>();
        if (n == null) return out;
        for (JsonNode x : n) {
            if (!x.isTextual()) throw new IllegalArgumentException(name + " must be a list of options");
            if (!out.contains(x.asText())) out.add(x.asText());
        }
        return out;
    }

    private List<Long> idArray(Object o, String name) {
        JsonNode n = arrayNode(o, name);
        List<Long> out = new ArrayList<>();
        if (n == null) return out;
        for (JsonNode x : n) {
            long id;
            if (x.isIntegralNumber() && x.canConvertToLong()) id = x.asLong();
            else if (x.isTextual()) id = parseId(x.asText(), name);
            else throw new IllegalArgumentException(name + " must be a list of ids");
            if (!out.contains(id)) out.add(id);
        }
        if (out.size() > MAX_LINKS) throw new IllegalArgumentException(name + " can link at most " + MAX_LINKS + " items");
        return out;
    }

    private static long parseId(String s, String name) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a list of ids");
        }
    }

    /** jsonValue may arrive as a JSON string ("[1,2]") or as an actual array. */
    private JsonNode arrayNode(Object o, String name) {
        if (o == null) return null;
        JsonNode n = o instanceof String s ? (s.isBlank() ? null : readJson(s)) : objectMapper.valueToTree(o);
        if (n == null) {
            if (o instanceof String s && !s.isBlank()) throw new IllegalArgumentException(name + " must be a JSON array");
            return null;
        }
        if (!n.isArray()) throw new IllegalArgumentException(name + " must be a JSON array");
        return (ArrayNode) n;
    }

    private List<Long> safeIdArray(String json) {
        List<Long> out = new ArrayList<>();
        JsonNode n = json == null ? null : readJson(json);
        if (n != null && n.isArray()) for (JsonNode x : n) if (x.canConvertToLong()) out.add(x.asLong());
        return out;
    }

    private List<String> safeStringArray(String json) {
        List<String> out = new ArrayList<>();
        JsonNode n = json == null ? null : readJson(json);
        if (n != null && n.isArray()) for (JsonNode x : n) out.add(x.asText());
        return out;
    }

    private JsonNode readJson(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("value is not serialisable");
        }
    }

    /** Exposed for tests/callers that already hold a field map. */
    public static Formula.Result evaluate(String expression, Function<String, Object> fields) {
        return Formula.parse(expression).evaluate(fields);
    }
}
