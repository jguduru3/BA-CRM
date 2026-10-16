package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.DatabaseValueService;
import com.betteranalytics.crm.service.Paging;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Custom databases (Notion-style): schema management, record CRUD, typed property values, views.
 *
 * Routes:
 *   GET    /api/databases                           — list all databases in org (?page=&size=)
 *   POST   /api/databases                           — create database
 *   GET    /api/databases/{dbId}                    — get database + properties + views
 *   PUT    /api/databases/{dbId}                    — partial update of database metadata
 *   DELETE /api/databases/{dbId}                    — delete database with its properties, views, records and values
 *
 *   GET    /api/databases/{dbId}/properties         — list properties ordered by position
 *   POST   /api/databases/{dbId}/properties         — add property (12 types, see DatabaseValueService)
 *   PUT    /api/databases/{dbId}/properties/{propId}— partial update of a property
 *   DELETE /api/databases/{dbId}/properties/{propId}— delete property (and its values)
 *
 *   GET    /api/databases/{dbId}/records            — list records ?q=&sort=createdAt|updatedAt|id&dir=&page=&size=&limit=
 *   POST   /api/databases/{dbId}/records            — create record + set initial values
 *   GET    /api/databases/{dbId}/records/{recId}    — get single record
 *   PUT    /api/databases/{dbId}/records/{recId}    — update the values sent (others untouched)
 *   DELETE /api/databases/{dbId}/records/{recId}    — soft-delete record
 *
 *   GET    /api/databases/{dbId}/views              — list views
 *   POST   /api/databases/{dbId}/views              — create view
 *   PUT    /api/databases/{dbId}/views/{viewId}     — partial update of view config
 *   DELETE /api/databases/{dbId}/views/{viewId}     — delete view
 *
 * A record envelope is {record, values, formulas: {propId: {value, error}},
 * relations: {recordId: title}}. Formula values are computed server-side on
 * every read by the sandboxed evaluator and are read-only; relation values are
 * validated to point at live records of the related database in the same org;
 * URL values must be http(s)/mailto (UrlFields).
 */
@RestController
@RequestMapping("/api/databases")
public class CustomDatabaseController {

    private static final Set<String> VIEW_TYPES = Set.of("table", "kanban", "board", "calendar", "timeline", "dashboard");
    private static final Set<String> RECORD_SORTS = Set.of("createdAt", "updatedAt", "id");
    private static final int MAX_RECORDS = 500;

    private final CustomDatabaseRepository  dbRepo;
    private final DatabasePropertyRepository propRepo;
    private final DatabaseRecordRepository   recRepo;
    private final PropertyValueRepository    valRepo;
    private final DatabaseViewRepository     viewRepo;
    private final DatabaseValueService       values;
    private final PermissionService          permissionService;
    private final AuditService               auditService;
    private final CurrentUser                currentUser;
    private final ObjectMapper               objectMapper;

    public CustomDatabaseController(CustomDatabaseRepository dbRepo,
                                    DatabasePropertyRepository propRepo,
                                    DatabaseRecordRepository recRepo,
                                    PropertyValueRepository valRepo,
                                    DatabaseViewRepository viewRepo,
                                    DatabaseValueService values,
                                    PermissionService permissionService,
                                    AuditService auditService,
                                    CurrentUser currentUser,
                                    ObjectMapper objectMapper) {
        this.dbRepo = dbRepo;
        this.propRepo = propRepo;
        this.recRepo = recRepo;
        this.valRepo = valRepo;
        this.viewRepo = viewRepo;
        this.values = values;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    // ── Database schema ─────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<CustomDatabase>> listDatabases(@RequestParam(required = false) Integer page,
                                                               @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.DATABASE, actor.getOrganizationId(), actor.getId());
        return ResponseEntity.ok(dbRepo.findByOrganizationId(actor.getOrganizationId(),
                Paging.of(page, size, 500, 500, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")))));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createDatabase(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.DATABASE, actor.getOrganizationId(), null);
        CustomDatabase body = bind(raw, CustomDatabase.class);
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        CustomDatabase db = new CustomDatabase();
        db.setOrganizationId(actor.getOrganizationId());
        db.setName(body.getName().trim());
        db.setIcon(body.getIcon());
        db.setColor(body.getColor());
        db.setDescription(body.getDescription());
        db.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        db.setCreatedAt(now);
        db.setUpdatedAt(now);
        CustomDatabase saved = dbRepo.save(db);
        auditService.log("create", "databases", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{dbId}")
    public ResponseEntity<Map<String, Object>> getDatabase(@PathVariable Long dbId) {
        User actor = currentUser.get();
        CustomDatabase db = findDb(actor, dbId, Action.VIEW);
        if (db == null) return ResponseEntity.notFound().build();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", db);
        result.put("properties", propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId()));
        result.put("views", viewRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId()));
        result.put("canEdit", permissionService.can(actor, Action.EDIT, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy()));
        result.put("canDelete", permissionService.can(actor, Action.DELETE, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy()));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{dbId}")
    @Transactional
    public ResponseEntity<CustomDatabase> updateDatabase(@PathVariable Long dbId, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        CustomDatabase db = findDb(actor, dbId, Action.EDIT);
        if (db == null) return ResponseEntity.notFound().build();
        CustomDatabase body = bind(raw, CustomDatabase.class);
        if (body.getName() != null && !body.getName().isBlank()) db.setName(body.getName().trim());
        if (raw.containsKey("icon")) db.setIcon(body.getIcon());
        if (raw.containsKey("color")) db.setColor(body.getColor());
        if (raw.containsKey("description")) db.setDescription(body.getDescription());
        db.setUpdatedAt(Instant.now());
        CustomDatabase saved = dbRepo.save(db);
        auditService.log("update", "databases", dbId, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    /** Deletes the database and all of its children atomically. */
    @DeleteMapping("/{dbId}")
    @Transactional
    public ResponseEntity<Void> deleteDatabase(@PathVariable Long dbId) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        CustomDatabase db = findDb(actor, dbId, Action.DELETE);
        if (db == null) return ResponseEntity.notFound().build();
        valRepo.deleteAllForDatabase(dbId, orgId);
        recRepo.hardDeleteAllForDatabase(dbId, orgId);
        viewRepo.deleteAllForDatabase(dbId, orgId);
        propRepo.deleteAllForDatabase(dbId, orgId);
        propRepo.clearRelationReferences(dbId, orgId);
        dbRepo.delete(db);
        auditService.log("delete", "databases", dbId, db.getName(), db, null);
        return ResponseEntity.noContent().build();
    }

    // ── Properties ──────────────────────────────────────────────────────────

    @GetMapping("/{dbId}/properties")
    public ResponseEntity<List<DatabaseProperty>> listProperties(@PathVariable Long dbId) {
        User actor = currentUser.get();
        if (findDb(actor, dbId, Action.VIEW) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId()));
    }

    @PostMapping("/{dbId}/properties")
    @Transactional
    public ResponseEntity<?> addProperty(@PathVariable Long dbId, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        if (findDb(actor, dbId, Action.EDIT) == null) return ResponseEntity.notFound().build();
        DatabaseProperty body = bind(raw, DatabaseProperty.class);
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        DatabaseProperty prop = new DatabaseProperty();
        prop.setDatabaseId(dbId);
        prop.setOrganizationId(actor.getOrganizationId());
        prop.setName(body.getName().trim());
        prop.setType(body.getType());
        prop.setOptionsJson(body.getOptionsJson());
        prop.setRelationDbId("relation".equals(body.getType()) ? body.getRelationDbId() : null);
        prop.setRequired(body.isRequired());
        prop.setPosition(raw.containsKey("position") ? body.getPosition()
                : propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId()).size());
        values.validateDefinition(prop, actor.getOrganizationId());
        Instant now = Instant.now();
        prop.setCreatedAt(now);
        prop.setUpdatedAt(now);
        DatabaseProperty saved = propRepo.save(prop);
        auditService.log("create", "database_properties", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{dbId}/properties/{propId}")
    @Transactional
    public ResponseEntity<DatabaseProperty> updateProperty(@PathVariable Long dbId, @PathVariable Long propId,
                                                            @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        DatabaseProperty prop = propRepo.findByIdAndOrganizationId(propId, actor.getOrganizationId()).orElse(null);
        if (prop == null || !Objects.equals(prop.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        if (findDb(actor, dbId, Action.EDIT) == null) return ResponseEntity.notFound().build();
        DatabaseProperty body = bind(raw, DatabaseProperty.class);
        if (body.getName() != null && !body.getName().isBlank()) prop.setName(body.getName().trim());
        if (raw.containsKey("optionsJson")) prop.setOptionsJson(body.getOptionsJson());
        if (raw.containsKey("relationDbId") && "relation".equals(prop.getType())) prop.setRelationDbId(body.getRelationDbId());
        if (raw.containsKey("required")) prop.setRequired(body.isRequired());
        if (raw.containsKey("position")) prop.setPosition(body.getPosition());
        values.validateDefinition(prop, actor.getOrganizationId());
        prop.setUpdatedAt(Instant.now());
        DatabaseProperty saved = propRepo.save(prop);
        auditService.log("update", "database_properties", propId, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{dbId}/properties/{propId}")
    @Transactional
    public ResponseEntity<Void> deleteProperty(@PathVariable Long dbId, @PathVariable Long propId) {
        User actor = currentUser.get();
        DatabaseProperty prop = propRepo.findByIdAndOrganizationId(propId, actor.getOrganizationId()).orElse(null);
        if (prop == null || !Objects.equals(prop.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        if (findDb(actor, dbId, Action.EDIT) == null) return ResponseEntity.notFound().build();
        valRepo.deleteByPropertyIdAndOrganizationId(propId, actor.getOrganizationId());
        propRepo.delete(prop);
        auditService.log("delete", "database_properties", propId, prop.getName(), prop, null);
        return ResponseEntity.noContent().build();
    }

    // ── Records ─────────────────────────────────────────────────────────────

    /**
     * Records are filtered, sorted and paged in the database; properties and the
     * page's values are each loaded with one query (no per-record queries), and
     * formulas/relation titles are computed in memory from those.
     */
    @GetMapping("/{dbId}/records")
    public ResponseEntity<List<Map<String, Object>>> listRecords(@PathVariable Long dbId,
                                                                 @RequestParam(required = false) String q,
                                                                 @RequestParam(required = false) String sort,
                                                                 @RequestParam(required = false) String dir,
                                                                 @RequestParam(required = false) Integer limit,
                                                                 @RequestParam(required = false) Integer page,
                                                                 @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        if (findDb(actor, dbId, Action.VIEW) == null) return ResponseEntity.notFound().build();
        String sortField = sort == null || sort.isBlank() ? "id" : sort;
        if (!RECORD_SORTS.contains(sortField)) throw new IllegalArgumentException("cannot sort records by " + sortField);
        Sort.Direction direction = "desc".equalsIgnoreCase(dir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        Specification<DatabaseRecord> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("databaseId"), dbId));
            ps.add(cb.equal(root.get("organizationId"), orgId));
            if (q != null && !q.isBlank()) {
                String like = "%" + ProjectController.escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                Subquery<Long> sq = query.subquery(Long.class);
                Root<PropertyValue> pv = sq.from(PropertyValue.class);
                sq.select(pv.get("recordId")).where(
                        cb.equal(pv.get("recordId"), root.get("id")),
                        cb.equal(pv.get("organizationId"), orgId),
                        cb.like(cb.lower(pv.get("textValue")), like, '\\'));
                ps.add(cb.exists(sq));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        List<DatabaseRecord> records = recRepo.findAll(spec, Paging.of(page, size, limit, MAX_RECORDS, MAX_RECORDS,
                Sort.by(new Sort.Order(direction, sortField), new Sort.Order(direction, "id")))).getContent();
        List<DatabaseProperty> props = propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, orgId);
        return ResponseEntity.ok(values.envelopes(records, props, orgId));
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
        values.applyValues(rec, body == null ? null : body.get("values"), actor.getOrganizationId(), true);
        auditService.log("create", "database_records", rec.getId(), db.getName() + " #" + rec.getId(), null, body);
        return ResponseEntity.ok(envelope(rec, actor.getOrganizationId()));
    }

    @GetMapping("/{dbId}/records/{recId}")
    public ResponseEntity<Map<String, Object>> getRecord(@PathVariable Long dbId, @PathVariable Long recId) {
        User actor = currentUser.get();
        DatabaseRecord rec = recRepo.findByIdAndOrganizationId(recId, actor.getOrganizationId()).orElse(null);
        if (rec == null || !Objects.equals(rec.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.VIEW, ResourceType.DATABASE, rec.getOrganizationId(), rec.getOwnerId());
        return ResponseEntity.ok(envelope(rec, actor.getOrganizationId()));
    }

    @PutMapping("/{dbId}/records/{recId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateRecord(@PathVariable Long dbId, @PathVariable Long recId,
                                                             @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        DatabaseRecord rec = recRepo.findByIdAndOrganizationId(recId, actor.getOrganizationId()).orElse(null);
        if (rec == null || !Objects.equals(rec.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.DATABASE, rec.getOrganizationId(), rec.getOwnerId());
        values.applyValues(rec, body == null ? null : body.get("values"), actor.getOrganizationId(), false);
        rec.setUpdatedAt(Instant.now());
        recRepo.save(rec);
        auditService.log("update", "database_records", recId, "record #" + recId, null, body);
        return ResponseEntity.ok(envelope(rec, actor.getOrganizationId()));
    }

    @DeleteMapping("/{dbId}/records/{recId}")
    @Transactional
    public ResponseEntity<Void> deleteRecord(@PathVariable Long dbId, @PathVariable Long recId) {
        User actor = currentUser.get();
        DatabaseRecord rec = recRepo.findByIdAndOrganizationId(recId, actor.getOrganizationId()).orElse(null);
        if (rec == null || !Objects.equals(rec.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.DATABASE, rec.getOrganizationId(), rec.getOwnerId());
        Instant now = Instant.now();
        rec.setDeletedAt(now);
        rec.setDeletedBy(actor.getId());
        recRepo.save(rec);
        auditService.log("delete", "database_records", recId, "record #" + recId, rec, null);
        return ResponseEntity.noContent().build();
    }

    // ── Views ────────────────────────────────────────────────────────────────

    @GetMapping("/{dbId}/views")
    public ResponseEntity<List<DatabaseView>> listViews(@PathVariable Long dbId) {
        User actor = currentUser.get();
        if (findDb(actor, dbId, Action.VIEW) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(viewRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(dbId, actor.getOrganizationId()));
    }

    @PostMapping("/{dbId}/views")
    @Transactional
    public ResponseEntity<?> createView(@PathVariable Long dbId, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        if (findDb(actor, dbId, Action.EDIT) == null) return ResponseEntity.notFound().build();
        DatabaseView body = bind(raw, DatabaseView.class);
        if (body.getName() == null || body.getName().isBlank()) return badRequest("name is required");
        if (body.getType() == null || !VIEW_TYPES.contains(body.getType())) return badRequest("unsupported view type");
        DatabaseView view = new DatabaseView();
        view.setDatabaseId(dbId);
        view.setOrganizationId(actor.getOrganizationId());
        view.setName(body.getName().trim());
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
        auditService.log("create", "database_views", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{dbId}/views/{viewId}")
    @Transactional
    public ResponseEntity<DatabaseView> updateView(@PathVariable Long dbId, @PathVariable Long viewId,
                                                    @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        DatabaseView view = viewRepo.findByIdAndOrganizationId(viewId, actor.getOrganizationId()).orElse(null);
        if (view == null || !Objects.equals(view.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        if (findDb(actor, dbId, Action.EDIT) == null) return ResponseEntity.notFound().build();
        DatabaseView body = bind(raw, DatabaseView.class);
        if (body.getName() != null && !body.getName().isBlank()) view.setName(body.getName().trim());
        if (raw.containsKey("configJson")) view.setConfigJson(body.getConfigJson());
        if (raw.containsKey("default")) view.setDefault(body.isDefault());
        if (raw.containsKey("position")) view.setPosition(body.getPosition());
        view.setUpdatedAt(Instant.now());
        DatabaseView saved = viewRepo.save(view);
        if (raw.containsKey("default") && saved.isDefault()) {
            viewRepo.clearOtherDefaults(dbId, actor.getOrganizationId(), saved.getId());
        }
        auditService.log("update", "database_views", viewId, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{dbId}/views/{viewId}")
    @Transactional
    public ResponseEntity<Void> deleteView(@PathVariable Long dbId, @PathVariable Long viewId) {
        User actor = currentUser.get();
        DatabaseView view = viewRepo.findByIdAndOrganizationId(viewId, actor.getOrganizationId()).orElse(null);
        if (view == null || !Objects.equals(view.getDatabaseId(), dbId)) return ResponseEntity.notFound().build();
        if (findDb(actor, dbId, Action.EDIT) == null) return ResponseEntity.notFound().build();
        viewRepo.delete(view);
        auditService.log("delete", "database_views", viewId, view.getName(), view, null);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Org-scoped lookup + permission check for the database (null → 404). */
    private CustomDatabase findDb(User actor, Long dbId, Action action) {
        CustomDatabase db = dbRepo.findByIdAndOrganizationId(dbId, actor.getOrganizationId()).orElse(null);
        if (db == null) return null;
        permissionService.require(actor, action, ResourceType.DATABASE, db.getOrganizationId(), db.getCreatedBy());
        return db;
    }

    private Map<String, Object> envelope(DatabaseRecord rec, Long orgId) {
        List<DatabaseProperty> props = propRepo.findByDatabaseIdAndOrganizationIdOrderByPosition(rec.getDatabaseId(), orgId);
        return values.envelopes(List.of(rec), props, orgId).get(0);
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
