package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Custom databases (Notion-style): schema management, record CRUD, property values, views.
 *
 * Routes:
 *   GET    /api/databases                           — list all databases in org
 *   POST   /api/databases                           — create database
 *   GET    /api/databases/{dbId}                    — get database + properties + views
 *   PUT    /api/databases/{dbId}                    — update database metadata
 *   DELETE /api/databases/{dbId}                    — delete database with its properties, views, records and values
 *
 *   GET    /api/databases/{dbId}/properties         — list properties ordered by position
 *   POST   /api/databases/{dbId}/properties         — add property
 *   PUT    /api/databases/{dbId}/properties/{propId}— update property
 *   DELETE /api/databases/{dbId}/properties/{propId}— delete property
 *
 *   GET    /api/databases/{dbId}/records            — list records (with values), ?limit= (1..500, default 500)
 *   POST   /api/databases/{dbId}/records            — create record + set initial values
 *   GET    /api/databases/{dbId}/records/{recId}    — get single record + values
 *   PUT    /api/databases/{dbId}/records/{recId}    — update record values
 *   DELETE /api/databases/{dbId}/records/{recId}    — soft-delete record
 *
 *   GET    /api/databases/{dbId}/views              — list views
 *   POST   /api/databases/{dbId}/views              — create view
 *   PUT    /api/databases/{dbId}/views/{viewId}     — update view config
 *   DELETE /api/databases/{dbId}/views/{viewId}     — delete view
 */
@RestController
@RequestMapping("/api/databases")
public class CustomDatabaseController {

    private static final Set<String> PROPERTY_TYPES = Set.of(
            "text", "number", "date", "select", "multi_select", "relation", "checkbox",
            "url", "email", "phone", "file", "formula");
    private static final Set<String> VIEW_TYPES = Set.of("table", "kanban", "calendar", "timeline", "dashboard");
    private static final int MAX_RECORDS = 500;

    private final CustomDatabaseRepository  dbRepo;
    private final DatabasePropertyRepository propRepo;
    private final DatabaseRecordRepository   recRepo;
    private final PropertyValueRepository    valRepo;
    private final DatabaseViewRepository     viewRepo;
    private final PermissionService          permissionService;
    private final CurrentUser                currentUser;
    private final ObjectMapper               objectMapper;

    public CustomDatabaseController(CustomDatabaseRepository dbRepo,
                                    DatabasePropertyRepository propRepo,
                                    DatabaseRecordRepository recRepo,
                                    PropertyValueRepository valRepo,
                                    DatabaseViewRepository viewRepo,
                                    PermissionService permissionService,
                                    CurrentUser currentUser,
                                    ObjectMapper objectMapper) {
        this.dbRepo = dbRepo;
        this.propRepo = propRepo;
        this.recRepo = recRepo;
        this.valRepo = valRepo;
        this.viewRepo = viewRepo;
        this.permissionService = permissionService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    // ── Database schema ─────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<CustomDatabase>> listDatabases() {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.DATABASE, actor.getOrganizationId(), null);
        return ResponseEntity.ok(dbRepo.findByOrganizationId(actor.getOrganizationId()));
    }

    @PostMapping
    public ResponseEntity<?> createDatabase(@RequestBody CustomDatabase body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.DATABASE, actor.getOrganizationId(), null);
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        CustomDatabase db = new CustomDatabase();
        db.setOrganizationId(actor.getOrganizationId());
        db.setName(body.getName());
        db.setIcon(body.getIcon());
        db.setColor(body.getColor());
        db.setDescription(body.getDescription());
        db.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        db.setCreatedAt(now);
        db.setUpdatedAt(now);
        return ResponseEntity.ok(dbRepo.save(db));
    }

    @GetMapping("/{dbId}")
    public ResponseEntity<Map<String, Object>> getDatabase(@PathVariable Long dbId) {
        User actor = currentUser.get();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        List<DatabaseProperty> props = propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId());
        List<DatabaseView> views = viewRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", db);
        result.put("properties", props);
        result.put("views", views);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{dbId}")
    public ResponseEntity<CustomDatabase> updateDatabase(@PathVariable Long dbId, @RequestBody CustomDatabase body) {
        User actor = currentUser.get();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        if (body.getName() != null && !body.getName().isBlank()) db.setName(body.getName());
        if (body.getIcon() != null) db.setIcon(body.getIcon());
        if (body.getColor() != null) db.setColor(body.getColor());
        if (body.getDescription() != null) db.setDescription(body.getDescription());
        db.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(dbRepo.save(db));
    }

    /** Deletes the database and all of its children atomically. */
    @DeleteMapping("/{dbId}")
    @Transactional
    public ResponseEntity<Void> deleteDatabase(@PathVariable Long dbId) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, orgId).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        valRepo.deleteAllForDatabase(dbId, orgId);
        recRepo.hardDeleteAllForDatabase(dbId, orgId);
        viewRepo.deleteAllForDatabase(dbId, orgId);
        propRepo.deleteAllForDatabase(dbId, orgId);
        propRepo.clearRelationReferences(dbId, orgId);
        dbRepo.delete(db);
        return ResponseEntity.noContent().build();
    }

    // ── Properties ──────────────────────────────────────────────────────────

    @GetMapping("/{dbId}/properties")
    public ResponseEntity<List<DatabaseProperty>> listProperties(@PathVariable Long dbId) {
        User actor = currentUser.get();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        return ResponseEntity.ok(propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId()));
    }

    @PostMapping("/{dbId}/properties")
    public ResponseEntity<?> addProperty(@PathVariable Long dbId, @RequestBody DatabaseProperty body) {
        User actor = currentUser.get();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        if (body.getType() == null || !PROPERTY_TYPES.contains(body.getType())) return badRequest("unsupported property type");
        if (body.getRelationDbId() != null
                && dbRepo.findByIdAndOrganizationId(body.getRelationDbId(), actor.getOrganizationId()).isEmpty()) {
            return badRequest("related database not found");
        }
        DatabaseProperty prop = new DatabaseProperty();
        prop.setDatabaseId(dbId);
        prop.setOrganizationId(actor.getOrganizationId());
        prop.setName(body.getName());
        prop.setType(body.getType());
        prop.setOptionsJson(body.getOptionsJson());
        prop.setRelationDbId("relation".equals(body.getType()) ? body.getRelationDbId() : null);
        prop.setRequired(body.isRequired());
        prop.setPosition(body.getPosition());
        Instant now = Instant.now();
        prop.setCreatedAt(now);
        prop.setUpdatedAt(now);
        return ResponseEntity.ok(propRepo.save(prop));
    }

    @PutMapping("/{dbId}/properties/{propId}")
    public ResponseEntity<DatabaseProperty> updateProperty(@PathVariable Long dbId, @PathVariable Long propId,
                                                            @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        DatabaseProperty prop = propRepo.findByIdAndOrganizationId(propId, actor.getOrganizationId()).orElse(null);
        if (prop == null || !Objects.equals(prop.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        DatabaseProperty body = bind(raw, DatabaseProperty.class);
        if (body.getName() != null && !body.getName().isBlank()) prop.setName(body.getName());
        if (body.getOptionsJson() != null) prop.setOptionsJson(body.getOptionsJson());
        if (raw.containsKey("required")) prop.setRequired(body.isRequired());
        if (raw.containsKey("position")) prop.setPosition(body.getPosition());
        prop.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(propRepo.save(prop));
    }

    @DeleteMapping("/{dbId}/properties/{propId}")
    @Transactional
    public ResponseEntity<Void> deleteProperty(@PathVariable Long dbId, @PathVariable Long propId) {
        User actor = currentUser.get();
        DatabaseProperty prop = propRepo.findByIdAndOrganizationId(propId, actor.getOrganizationId()).orElse(null);
        if (prop == null || !Objects.equals(prop.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        valRepo.deleteByPropertyIdAndOrganizationId(propId, actor.getOrganizationId());
        propRepo.delete(prop);
        return ResponseEntity.noContent().build();
    }

    // ── Records ─────────────────────────────────────────────────────────────

    @GetMapping("/{dbId}/records")
    public ResponseEntity<List<Map<String, Object>>> listRecords(@PathVariable Long dbId,
                                                                 @RequestParam(defaultValue = "500") int limit) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, orgId).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        int capped = Math.max(1, Math.min(limit, MAX_RECORDS));
        List<DatabaseRecord> records = recRepo.findByDatabaseIdAndOrganizationId(dbId, orgId,
                PageRequest.of(0, capped, Sort.by(Sort.Direction.ASC, "id")));
        if (records.isEmpty()) return ResponseEntity.ok(List.of());

        // One query for every value of this page of records (was one query per record).
        Map<Long, List<PropertyValue>> valuesByRecord = valRepo
                .findByRecordIdInAndOrganizationId(records.stream().map(DatabaseRecord::getId).toList(), orgId)
                .stream().collect(Collectors.groupingBy(PropertyValue::getRecordId));
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (DatabaseRecord rec : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("record", rec);
            m.put("values", valuesByRecord.getOrDefault(rec.getId(), List.of()));
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{dbId}/records")
    @Transactional
    public ResponseEntity<Map<String, Object>> createRecord(@PathVariable Long dbId,
                                                             @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.CREATE, ResourceType.DATABASE, db.getOrganizationId(), null);
        DatabaseRecord rec = new DatabaseRecord();
        rec.setDatabaseId(dbId);
        rec.setOrganizationId(actor.getOrganizationId());
        rec.setOwnerId(actor.getId());
        rec.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        rec.setCreatedAt(now);
        rec.setUpdatedAt(now);
        rec = recRepo.save(rec);
        setPropertyValues(rec, body, actor.getOrganizationId());
        return ResponseEntity.ok(recordWithValues(rec, actor.getOrganizationId()));
    }

    @GetMapping("/{dbId}/records/{recId}")
    public ResponseEntity<Map<String, Object>> getRecord(@PathVariable Long dbId, @PathVariable Long recId) {
        User actor = currentUser.get();
        DatabaseRecord rec = recRepo.findByIdAndOrganizationId(recId, actor.getOrganizationId()).orElse(null);
        if (rec == null || !Objects.equals(rec.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.DATABASE, rec.getOrganizationId(), rec.getOwnerId());
        return ResponseEntity.ok(recordWithValues(rec, actor.getOrganizationId()));
    }

    @PutMapping("/{dbId}/records/{recId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateRecord(@PathVariable Long dbId, @PathVariable Long recId,
                                                             @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        DatabaseRecord rec = recRepo.findByIdAndOrganizationId(recId, actor.getOrganizationId()).orElse(null);
        if (rec == null || !Objects.equals(rec.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, rec.getOrganizationId(), rec.getOwnerId());
        setPropertyValues(rec, body, actor.getOrganizationId());
        rec.setUpdatedAt(Instant.now());
        recRepo.save(rec);
        return ResponseEntity.ok(recordWithValues(rec, actor.getOrganizationId()));
    }

    @DeleteMapping("/{dbId}/records/{recId}")
    public ResponseEntity<Void> deleteRecord(@PathVariable Long dbId, @PathVariable Long recId) {
        User actor = currentUser.get();
        DatabaseRecord rec = recRepo.findByIdAndOrganizationId(recId, actor.getOrganizationId()).orElse(null);
        if (rec == null || !Objects.equals(rec.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.DATABASE, rec.getOrganizationId(), rec.getOwnerId());
        Instant now = Instant.now();
        rec.setDeletedAt(now);
        rec.setDeletedBy(actor.getId());
        recRepo.save(rec);
        return ResponseEntity.noContent().build();
    }

    // ── Views ────────────────────────────────────────────────────────────────

    @GetMapping("/{dbId}/views")
    public ResponseEntity<List<DatabaseView>> listViews(@PathVariable Long dbId) {
        User actor = currentUser.get();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        return ResponseEntity.ok(viewRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId()));
    }

    @PostMapping("/{dbId}/views")
    @Transactional
    public ResponseEntity<?> createView(@PathVariable Long dbId, @RequestBody DatabaseView body) {
        User actor = currentUser.get();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        if (body.getType() == null || !VIEW_TYPES.contains(body.getType())) return badRequest("unsupported view type");
        DatabaseView view = new DatabaseView();
        view.setDatabaseId(dbId);
        view.setOrganizationId(actor.getOrganizationId());
        view.setName(body.getName());
        view.setType(body.getType());
        view.setConfigJson(body.getConfigJson());
        view.setDefault(body.isDefault());
        view.setPosition(body.getPosition());
        view.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        view.setCreatedAt(now);
        view.setUpdatedAt(now);
        DatabaseView saved = viewRepo.save(view);
        if (saved.isDefault()) viewRepo.clearOtherDefaults(dbId, actor.getOrganizationId(), saved.getId());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{dbId}/views/{viewId}")
    @Transactional
    public ResponseEntity<DatabaseView> updateView(@PathVariable Long dbId, @PathVariable Long viewId,
                                                    @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        DatabaseView view = viewRepo.findByIdAndOrganizationId(viewId, actor.getOrganizationId()).orElse(null);
        if (view == null || !Objects.equals(view.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        DatabaseView body = bind(raw, DatabaseView.class);
        if (body.getName() != null && !body.getName().isBlank()) view.setName(body.getName());
        if (body.getConfigJson() != null) view.setConfigJson(body.getConfigJson());
        if (raw.containsKey("default")) view.setDefault(body.isDefault());
        if (raw.containsKey("position")) view.setPosition(body.getPosition());
        view.setUpdatedAt(Instant.now());
        DatabaseView saved = viewRepo.save(view);
        if (raw.containsKey("default") && saved.isDefault()) {
            viewRepo.clearOtherDefaults(dbId, actor.getOrganizationId(), saved.getId());
        }
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{dbId}/views/{viewId}")
    public ResponseEntity<Void> deleteView(@PathVariable Long dbId, @PathVariable Long viewId) {
        User actor = currentUser.get();
        DatabaseView view = viewRepo.findByIdAndOrganizationId(viewId, actor.getOrganizationId()).orElse(null);
        if (view == null || !Objects.equals(view.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        viewRepo.delete(view);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> recordWithValues(DatabaseRecord rec, Long orgId) {
        List<PropertyValue> values = valRepo.findByRecordIdAndOrganizationId(rec.getId(), orgId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("record", rec);
        m.put("values", values);
        return m;
    }

    /**
     * Applies {"values":[{propertyId, textValue?, numberValue?, boolValue?, dateValue?, jsonValue?}]}.
     * Every propertyId must belong to the record's own database (else 400).
     * A key that is present with JSON null clears that column (never stores the string "null").
     * Malformed shapes/types are rejected with IllegalArgumentException (400).
     */
    private void setPropertyValues(DatabaseRecord rec, Map<String, Object> body, Long orgId) {
        if (body == null) return;
        Object valuesObj = body.get("values");
        if (valuesObj == null) return;
        if (!(valuesObj instanceof List<?> valuesList)) throw new IllegalArgumentException("values must be a list");

        Set<Long> allowedPropertyIds = propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(rec.getDatabaseId(), orgId)
                .stream().map(DatabaseProperty::getId).collect(Collectors.toSet());

        for (Object item : valuesList) {
            if (!(item instanceof Map<?, ?> v)) throw new IllegalArgumentException("each value must be an object");
            if (!(v.get("propertyId") instanceof Number pidNum)) throw new IllegalArgumentException("propertyId must be a number");
            Long propertyId = pidNum.longValue();
            if (!allowedPropertyIds.contains(propertyId)) {
                throw new IllegalArgumentException("property " + propertyId + " does not belong to this database");
            }
            PropertyValue pv = valRepo.findByRecordIdAndPropertyIdAndOrganizationId(rec.getId(), propertyId, orgId)
                    .orElseGet(() -> {
                        PropertyValue newPv = new PropertyValue();
                        newPv.setRecordId(rec.getId());
                        newPv.setPropertyId(propertyId);
                        newPv.setOrganizationId(orgId);
                        return newPv;
                    });
            if (v.containsKey("textValue")) {
                Object t = v.get("textValue");
                pv.setTextValue(t == null ? null : (t instanceof String s ? s : String.valueOf(t)));
            }
            if (v.containsKey("numberValue")) {
                Object n = v.get("numberValue");
                if (n == null) pv.setNumberValue(null);
                else if (n instanceof Number) pv.setNumberValue(new BigDecimal(n.toString()));
                else throw new IllegalArgumentException("numberValue must be a number");
            }
            if (v.containsKey("boolValue")) {
                Object b = v.get("boolValue");
                if (b == null) pv.setBoolValue(null);
                else if (b instanceof Boolean bool) pv.setBoolValue(bool);
                else throw new IllegalArgumentException("boolValue must be a boolean");
            }
            if (v.containsKey("dateValue")) {
                Object d = v.get("dateValue");
                if (d == null) pv.setDateValue(null);
                else if (d instanceof String s) {
                    try {
                        pv.setDateValue(Instant.parse(s));
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("dateValue must be an ISO-8601 instant");
                    }
                } else throw new IllegalArgumentException("dateValue must be an ISO-8601 string");
            }
            if (v.containsKey("jsonValue")) {
                Object j = v.get("jsonValue");
                if (j == null) pv.setJsonValue(null);
                else if (j instanceof String s) pv.setJsonValue(s);
                else {
                    try {
                        pv.setJsonValue(objectMapper.writeValueAsString(j));
                    } catch (JsonProcessingException e) {
                        throw new IllegalArgumentException("jsonValue is not serialisable");
                    }
                }
            }
            valRepo.save(pv);
        }
    }

    private static ResponseEntity<Map<String, String>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** Typed view of a JSON body; type mismatches become IllegalArgumentException (400). */
    private <T> T bind(Map<String, Object> raw, Class<T> type) {
        if (raw == null) throw new IllegalArgumentException("request body is required");
        try {
            return objectMapper.convertValue(raw, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid request body");
        }
    }
}
