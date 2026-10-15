package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Record pages — one block-based page per CRM record (or per Document).
 *
 * GET    /api/pages/{type}/{id}                    — page + ordered blocks (an unsaved, empty page if none yet)
 * PUT    /api/pages/{type}/{id}                    — update page metadata (icon, cover) — creates the page on first write
 * DELETE /api/pages/{type}/{id}                    — delete the page and all of its blocks
 * GET    /api/pages/{type}/{id}/blocks
 * POST   /api/pages/{type}/{id}/blocks             — add a block (appended, or after {@code afterBlockId})
 * PUT    /api/pages/{type}/{id}/blocks/order       — {"blockIds":[...]} rewrites every position in one transaction
 * PUT    /api/pages/{type}/{id}/blocks/{blockId}   — partial update (only the keys sent change)
 * DELETE /api/pages/{type}/{id}/blocks/{blockId}
 *
 * Blocks are ordered rows; {@code content} is a JSON document. The editor's
 * block types are paragraph, heading_1/2/3, bulleted_list, todo and divider,
 * whose content is {"text": "..."} (plain text — clients must render it as
 * text, never as HTML). Older block types are still accepted as-is.
 *
 * Access follows the parent record: reading needs VIEW on it, writing needs
 * EDIT on it (plus the RECORD_PAGE feature permission). GETs never insert rows.
 */
@RestController
@RequestMapping("/api/pages")
public class RecordPageController {

    private static final Set<String> RELATED_TYPES = Set.of(
            "Lead", "Opportunity", "Contact", "Company", "Task", "DatabaseRecord", "CustomRecord", "Document");
    /** Editor blocks whose content must be a {"text": ...} JSON object. */
    private static final Set<String> TEXT_BLOCK_TYPES = Set.of(
            "paragraph", "heading_1", "heading_2", "heading_3", "bulleted_list", "todo");
    private static final Set<String> BLOCK_TYPES;
    static {
        Set<String> all = new HashSet<>(TEXT_BLOCK_TYPES);
        all.add("divider");
        // legacy types
        all.addAll(Set.of("text", "heading", "checklist", "embed", "property_table", "ai_summary"));
        BLOCK_TYPES = Collections.unmodifiableSet(all);
    }
    private static final int MAX_BLOCKS_PER_PAGE = 2000;
    private static final int MAX_CONTENT_CHARS = 20_000;

    private final RecordPageRepository pageRepository;
    private final PageBlockRepository blockRepository;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final ContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final TaskRepository taskRepository;
    private final DatabaseRecordRepository databaseRecordRepository;
    private final DocumentRepository documentRepository;

    public RecordPageController(RecordPageRepository pageRepository, PageBlockRepository blockRepository,
                                 CurrentUser currentUser, PermissionService permissionService,
                                 AuditService auditService, ObjectMapper objectMapper,
                                 LeadRepository leadRepository, OpportunityRepository opportunityRepository,
                                 ContactRepository contactRepository, CompanyRepository companyRepository,
                                 TaskRepository taskRepository, DatabaseRecordRepository databaseRecordRepository,
                                 DocumentRepository documentRepository) {
        this.pageRepository = pageRepository;
        this.blockRepository = blockRepository;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.taskRepository = taskRepository;
        this.databaseRecordRepository = databaseRecordRepository;
        this.documentRepository = documentRepository;
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    @GetMapping("/{relatedType}/{relatedId}")
    public ResponseEntity<Map<String, Object>> getPage(@PathVariable String relatedType,
                                                        @PathVariable Long relatedId) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, false);
        RecordPage page = findPage(relatedType, relatedId).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        if (page == null) {
            // Virtual page: nothing is persisted until the first write.
            RecordPage virtual = new RecordPage();
            virtual.setOrganizationId(actor.getOrganizationId());
            virtual.setRelatedType(relatedType);
            virtual.setRelatedId(relatedId);
            result.put("page", virtual);
            result.put("blocks", List.of());
        } else {
            result.put("page", page);
            result.put("blocks", blockRepository.findByPageIdOrderByPositionAscIdAsc(page.getId()));
        }
        result.put("canEdit", canWrite(actor, relatedType, relatedId));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{relatedType}/{relatedId}")
    @Transactional
    public ResponseEntity<RecordPage> updatePage(@PathVariable String relatedType,
                                                  @PathVariable Long relatedId,
                                                  @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        RecordPage page = getOrCreate(actor, relatedType, relatedId);
        if (raw.containsKey("icon")) page.setIcon(str(raw, "icon"));
        if (raw.containsKey("coverColor")) page.setCoverColor(str(raw, "coverColor"));
        page.setUpdatedAt(Instant.now());
        pageRepository.save(page);
        auditService.log("update", "record_pages", page.getId(), relatedType + " #" + relatedId, null, page);
        return ResponseEntity.ok(page);
    }

    /** Deletes the page and every block (page_blocks.page_id also cascades in the DB). */
    @DeleteMapping("/{relatedType}/{relatedId}")
    @Transactional
    public ResponseEntity<?> deletePage(@PathVariable String relatedType, @PathVariable Long relatedId) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        RecordPage page = findPage(relatedType, relatedId).orElse(null);
        if (page == null) return ResponseEntity.ok(Map.of("ok", true, "deletedBlocks", 0));
        int blocks = blockRepository.deleteAllForPage(page.getId());
        pageRepository.deleteById(page.getId());
        auditService.log("delete", "record_pages", page.getId(), relatedType + " #" + relatedId,
                Map.of("blocks", blocks), null);
        return ResponseEntity.ok(Map.of("ok", true, "deletedBlocks", blocks));
    }

    // ── Blocks ────────────────────────────────────────────────────────────────

    @GetMapping("/{relatedType}/{relatedId}/blocks")
    public List<PageBlock> listBlocks(@PathVariable String relatedType, @PathVariable Long relatedId) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, false);
        return findPage(relatedType, relatedId)
                .map(p -> blockRepository.findByPageIdOrderByPositionAscIdAsc(p.getId()))
                .orElse(List.of());
    }

    @PostMapping("/{relatedType}/{relatedId}/blocks")
    @Transactional
    public ResponseEntity<?> addBlock(@PathVariable String relatedType,
                                      @PathVariable Long relatedId,
                                      @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        if (raw == null) throw new IllegalArgumentException("request body is required");
        String type = raw.containsKey("blockType") ? str(raw, "blockType") : "paragraph";
        if (type == null || !BLOCK_TYPES.contains(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported blockType"));
        }
        RecordPage page = getOrCreate(actor, relatedType, relatedId);
        if (blockRepository.countByPageId(page.getId()) >= MAX_BLOCKS_PER_PAGE) {
            return ResponseEntity.status(409).body(Map.of("error", "a page can hold at most " + MAX_BLOCKS_PER_PAGE + " blocks"));
        }
        int position;
        Long afterId = longVal(raw, "afterBlockId");
        if (afterId != null) {
            PageBlock after = blockRepository.findByIdAndPageId(afterId, page.getId())
                    .orElseThrow(() -> new IllegalArgumentException("afterBlockId is not a block of this page"));
            blockRepository.shiftAfter(page.getId(), after.getPosition());
            position = after.getPosition() + 1;
        } else if (raw.containsKey("position")) {
            position = (int) Math.max(0, longVal(raw, "position") == null ? 0 : longVal(raw, "position"));
        } else {
            position = blockRepository.maxPosition(page.getId()) + 1;
        }
        PageBlock block = new PageBlock();
        block.setPageId(page.getId());
        block.setBlockType(type);
        block.setContent(normalizeContent(type, raw.containsKey("content") ? raw.get("content") : null));
        block.setPosition(position);
        block.setChecked(Boolean.TRUE.equals(raw.get("checked")));
        block.setCreatedAt(Instant.now());
        block.setUpdatedAt(Instant.now());
        blockRepository.save(block);
        touch(page);
        return ResponseEntity.status(201).body(block);
    }

    /**
     * Reorders the page in one transaction: {"blockIds": [...]} must list every
     * block of the page exactly once; positions become 0..n-1 in that order.
     */
    @PutMapping("/{relatedType}/{relatedId}/blocks/order")
    @Transactional
    public ResponseEntity<?> reorder(@PathVariable String relatedType,
                                     @PathVariable Long relatedId,
                                     @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        RecordPage page = findPage(relatedType, relatedId).orElse(null);
        if (page == null) return ResponseEntity.notFound().build();
        if (raw == null || !(raw.get("blockIds") instanceof List<?> idsRaw)) {
            throw new IllegalArgumentException("blockIds must be a list");
        }
        List<Long> ids = new ArrayList<>(idsRaw.size());
        for (Object o : idsRaw) {
            if (!(o instanceof Number n)) throw new IllegalArgumentException("blockIds must be numbers");
            ids.add(n.longValue());
        }
        List<PageBlock> blocks = blockRepository.findByPageIdOrderByPositionAscIdAsc(page.getId());
        Map<Long, PageBlock> byId = new HashMap<>();
        for (PageBlock b : blocks) byId.put(b.getId(), b);
        if (ids.size() != blocks.size() || new HashSet<>(ids).size() != ids.size() || !byId.keySet().containsAll(ids)) {
            throw new IllegalArgumentException("blockIds must contain every block of this page exactly once");
        }
        Instant now = Instant.now();
        for (int i = 0; i < ids.size(); i++) {
            PageBlock b = byId.get(ids.get(i));
            if (b.getPosition() != i) {
                b.setPosition(i);
                b.setUpdatedAt(now);
            }
        }
        blockRepository.saveAll(blocks);
        touch(page);
        return ResponseEntity.ok(blockRepository.findByPageIdOrderByPositionAscIdAsc(page.getId()));
    }

    @PutMapping("/{relatedType}/{relatedId}/blocks/{blockId}")
    @Transactional
    public ResponseEntity<?> updateBlock(@PathVariable String relatedType,
                                         @PathVariable Long relatedId,
                                         @PathVariable Long blockId,
                                         @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        RecordPage page = findPage(relatedType, relatedId).orElse(null);
        if (page == null) return ResponseEntity.notFound().build();
        PageBlock existing = blockRepository.findByIdAndPageId(blockId, page.getId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (raw == null) throw new IllegalArgumentException("request body is required");
        String type = existing.getBlockType();
        if (raw.containsKey("blockType")) {
            type = str(raw, "blockType");
            if (type == null || !BLOCK_TYPES.contains(type)) {
                return ResponseEntity.badRequest().body(Map.of("error", "unsupported blockType"));
            }
        }
        if (raw.containsKey("content")) {
            existing.setContent(normalizeContent(type, raw.get("content")));
        } else if (!type.equals(existing.getBlockType())) {
            // type change (e.g. paragraph → heading_2) keeps the text but re-validates its shape
            existing.setContent(normalizeContent(type, existing.getContent()));
        }
        existing.setBlockType(type);
        if (raw.containsKey("position")) {
            Long p = longVal(raw, "position");
            existing.setPosition(p == null ? 0 : (int) Math.max(0, p));
        }
        if (raw.containsKey("checked")) {
            Object c = raw.get("checked");
            if (!(c instanceof Boolean b)) throw new IllegalArgumentException("checked must be a boolean");
            existing.setChecked(b);
        }
        existing.setUpdatedAt(Instant.now());
        blockRepository.save(existing);
        touch(page);
        return ResponseEntity.ok(existing);
    }

    @DeleteMapping("/{relatedType}/{relatedId}/blocks/{blockId}")
    @Transactional
    public ResponseEntity<?> deleteBlock(@PathVariable String relatedType,
                                          @PathVariable Long relatedId,
                                          @PathVariable Long blockId) {
        User actor = currentUser.get();
        requireAccess(actor, relatedType, relatedId, true);
        RecordPage page = findPage(relatedType, relatedId).orElse(null);
        if (page == null) return ResponseEntity.notFound().build();
        PageBlock existing = blockRepository.findByIdAndPageId(blockId, page.getId()).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        blockRepository.delete(existing);
        touch(page);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void touch(RecordPage page) {
        page.setUpdatedAt(Instant.now());
        pageRepository.save(page);
    }

    /**
     * Stores block content as JSON text. Editor (text) blocks must be a JSON
     * object with a string "text"; a bare string is wrapped as {"text": s}.
     * Dividers carry no content. Legacy types keep whatever string they had.
     */
    String normalizeContent(String type, Object content) {
        if ("divider".equals(type)) return null;
        if (!TEXT_BLOCK_TYPES.contains(type)) {
            if (content == null) return null;
            String s = content instanceof String str ? str : toJson(content);
            if (s.length() > MAX_CONTENT_CHARS) throw new IllegalArgumentException("block content is too long");
            return s;
        }
        JsonNode node;
        if (content == null) {
            node = objectMapper.createObjectNode().put("text", "");
        } else if (content instanceof String s) {
            node = parseObjectOrNull(s);
            if (node == null) node = objectMapper.createObjectNode().put("text", s);
        } else {
            node = objectMapper.valueToTree(content);
        }
        if (!node.isObject()) throw new IllegalArgumentException("block content must be a JSON object");
        JsonNode text = node.get("text");
        if (text != null && !text.isNull() && !text.isTextual()) {
            throw new IllegalArgumentException("block content.text must be a string");
        }
        String json = toJson(node);
        if (json.length() > MAX_CONTENT_CHARS) throw new IllegalArgumentException("block content is too long");
        return json;
    }

    private JsonNode parseObjectOrNull(String s) {
        String t = s.trim();
        if (!t.startsWith("{")) return null;
        try {
            JsonNode n = objectMapper.readTree(t);
            return n != null && n.isObject() ? n : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("block content is not valid JSON");
        }
    }

    private Optional<RecordPage> findPage(String relatedType, Long relatedId) {
        return pageRepository.findByOrganizationIdAndRelatedTypeAndRelatedId(
                currentUser.organizationId(), relatedType, relatedId);
    }

    /**
     * Race-safe get-or-create: record_pages has UNIQUE (organization_id,
     * related_type, related_id), so if a concurrent request inserts first our
     * insert fails and we just re-read the winner's row.
     */
    private RecordPage getOrCreate(User actor, String relatedType, Long relatedId) {
        Optional<RecordPage> existing = findPage(relatedType, relatedId);
        if (existing.isPresent()) return existing.get();
        permissionService.require(actor, Action.CREATE, ResourceType.RECORD_PAGE, actor.getOrganizationId(), null);
        RecordPage p = new RecordPage();
        p.setOrganizationId(actor.getOrganizationId());
        p.setRelatedType(relatedType);
        p.setRelatedId(relatedId);
        p.setCreatedBy(actor.getId());
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        try {
            return pageRepository.saveAndFlush(p);
        } catch (DataIntegrityViolationException raced) {
            return findPage(relatedType, relatedId).orElseThrow(() -> raced);
        }
    }

    private boolean canWrite(User actor, String relatedType, Long relatedId) {
        try {
            requireAccess(actor, relatedType, relatedId, true);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    /**
     * Validates the parent record exists in the caller's org and enforces
     * VIEW (write=false) / EDIT (write=true) on both the parent and RECORD_PAGE.
     */
    private void requireAccess(User actor, String relatedType, Long relatedId, boolean write) {
        if (relatedType == null || relatedId == null || !RELATED_TYPES.contains(relatedType)) {
            throw new IllegalArgumentException("unsupported record type");
        }
        Long orgId = actor.getOrganizationId();
        Action action = write ? Action.EDIT : Action.VIEW;
        permissionService.require(actor, action, ResourceType.RECORD_PAGE, orgId, null);
        switch (relatedType) {
            case "Lead" -> {
                Lead l = leadRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.LEAD, l.getOrganizationId(), l.getOwnerId());
            }
            case "Opportunity" -> {
                Opportunity o = opportunityRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                boolean ok = permissionService.can(actor, action, ResourceType.OPPORTUNITY, o.getOrganizationId(), o.getOwnerId())
                        || (!write && permissionService.canViewSourcedOpportunity(actor, o));
                if (!ok) throw new AccessDeniedException("not permitted to access this opportunity's page");
            }
            case "Contact" -> {
                Contact c = contactRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.CONTACT, c.getOrganizationId(), c.getOwnerId());
            }
            case "Company" -> {
                Company c = companyRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.COMPANY, c.getOrganizationId(), c.getOwnerId());
            }
            case "Task" -> {
                Task t = taskRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.TASK, t.getOrganizationId(), t.getAssigneeId());
            }
            case "DatabaseRecord", "CustomRecord" -> {
                DatabaseRecord r = databaseRecordRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.DATABASE, r.getOrganizationId(), r.getOwnerId());
            }
            case "Document" -> {
                Document d = documentRepository.findByIdAndOrganizationId(relatedId, orgId).orElseThrow(this::notFound);
                permissionService.require(actor, action, ResourceType.DOCUMENT, d.getOrganizationId(), d.getOwnerId());
            }
            default -> throw notFound();
        }
    }

    private NoSuchElementException notFound() {
        return new NoSuchElementException("record not found");
    }

    private static String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw new IllegalArgumentException(key + " must be a string");
    }

    private static Long longVal(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        throw new IllegalArgumentException(key + " must be a number");
    }
}
