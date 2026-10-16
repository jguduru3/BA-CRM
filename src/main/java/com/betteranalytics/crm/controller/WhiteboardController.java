package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Attachment;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.WhiteboardBoard;
import com.betteranalytics.crm.entity.WhiteboardItem;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.AttachmentRepository;
import com.betteranalytics.crm.repository.WhiteboardBoardRepository;
import com.betteranalytics.crm.repository.WhiteboardItemRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.FileStorageService;
import com.betteranalytics.crm.service.Paging;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Whiteboards — a Jamboard/Miro-style freeform canvas for planning and
 * brainstorming, separate from the structured CRM/database objects.
 *
 * GET    /api/whiteboards                    — list boards (?page=&size=), each with its itemCount
 * POST   /api/whiteboards                    — create board
 * GET    /api/whiteboards/{id}               — board + items (one query)
 * PUT    /api/whiteboards/{id}               — rename/describe board
 * DELETE /api/whiteboards/{id}               — delete board with its items, connectors and uploaded files
 *
 * POST   /api/whiteboards/{id}/items         — add an item (sticky/text/shape/image/connector)
 * PUT    /api/whiteboards/items/{itemId}     — partial update of one item (move/resize/edit/recolor)
 * DELETE /api/whiteboards/items/{itemId}     — remove an item (and every connector attached to it)
 * POST   /api/whiteboards/{id}/items/bulk    — batch position update (drag-end save)
 *
 * Connectors are items of type "connector" with fromItemId/toItemId (two
 * non-connector items on the same board), an optional arrowhead
 * (none|end|start|both, default end) and an optional label in {@code content}.
 * They carry no geometry of their own — clients draw them from the endpoints'
 * current positions, so they follow the elements as they move.
 *
 * Access: PermissionService on WHITEBOARD — everyone can view, any sales role
 * can create and edit (collaborative canvas), deleting a board needs DELETE
 * scope on its creator (own board for reps, any for Manager+), and deleting an
 * item needs DELETE scope on its author or on the board's creator.
 */
@RestController
@RequestMapping("/api/whiteboards")
public class WhiteboardController {

    private static final int MAX_BULK_ITEMS = 500;
    private static final Set<String> ITEM_TYPES = Set.of("sticky", "text", "shape", "image", "connector");
    private static final Set<String> ARROWHEADS = Set.of("none", "end", "start", "both");

    private final WhiteboardBoardRepository boardRepo;
    private final WhiteboardItemRepository itemRepo;
    private final AttachmentRepository attachmentRepo;
    private final FileStorageService storageService;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public WhiteboardController(WhiteboardBoardRepository boardRepo, WhiteboardItemRepository itemRepo,
                                 AttachmentRepository attachmentRepo, FileStorageService storageService,
                                 CurrentUser currentUser, PermissionService permissionService,
                                 AuditService auditService) {
        this.boardRepo = boardRepo;
        this.itemRepo = itemRepo;
        this.attachmentRepo = attachmentRepo;
        this.storageService = storageService;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        permissionService.require(actor, Action.VIEW, ResourceType.WHITEBOARD, orgId, actor.getId());
        List<WhiteboardBoard> boards = boardRepo.findByOrganizationId(orgId,
                Paging.of(page, size, 500, 500, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
        Map<Long, Long> counts = new HashMap<>();
        if (!boards.isEmpty()) {
            for (Object[] r : itemRepo.countByBoard(orgId, boards.stream().map(WhiteboardBoard::getId).toList())) {
                counts.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
            }
        }
        // Same fields as before plus itemCount — computed with one grouped query, not per board.
        List<Map<String, Object>> out = new ArrayList<>(boards.size());
        for (WhiteboardBoard b : boards) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("organizationId", b.getOrganizationId());
            m.put("name", b.getName());
            m.put("description", b.getDescription());
            m.put("createdBy", b.getCreatedBy());
            m.put("createdAt", b.getCreatedAt());
            m.put("updatedAt", b.getUpdatedAt());
            m.put("itemCount", counts.getOrDefault(b.getId(), 0L));
            out.add(m);
        }
        return out;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<WhiteboardBoard> create(@RequestBody WhiteboardBoard body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.WHITEBOARD, actor.getOrganizationId(), null);
        WhiteboardBoard board = new WhiteboardBoard();
        board.setOrganizationId(actor.getOrganizationId());
        board.setName(body.getName() != null && !body.getName().isBlank() ? body.getName().trim() : "Untitled board");
        board.setDescription(body.getDescription());
        board.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        board.setCreatedAt(now);
        board.setUpdatedAt(now);
        boardRepo.save(board);
        auditService.log("create", "whiteboards", board.getId(), board.getName(), null, board);
        return ResponseEntity.status(201).body(board);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        User actor = currentUser.get();
        WhiteboardBoard board = findBoard(actor, id);
        if (board == null) return ResponseEntity.notFound().build();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("board", board);
        result.put("items", itemRepo.findBoardItems(id, actor.getOrganizationId()));
        result.put("canEdit", permissionService.can(actor, Action.EDIT, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy()));
        result.put("canDelete", permissionService.can(actor, Action.DELETE, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy()));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<WhiteboardBoard> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        WhiteboardBoard board = findBoard(actor, id);
        if (board == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy());
        String name = str(body, "name");
        if (name != null && !name.isBlank()) board.setName(name.trim());
        if (body.containsKey("description")) board.setDescription(str(body, "description"));
        board.setUpdatedAt(Instant.now());
        WhiteboardBoard saved = boardRepo.save(board);
        auditService.log("update", "whiteboards", id, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    /**
     * Deletes the board, its items (connectors first) and the files uploaded to
     * it. Attachment rows go in the same transaction; the stored blobs are removed
     * only after commit, so a rollback never loses a file that is still referenced.
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        WhiteboardBoard board = findBoard(actor, id);
        if (board == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.DELETE, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy());
        itemRepo.deleteConnectorsOfBoard(id, orgId);
        int items = itemRepo.deleteAllOfBoard(id, orgId);
        List<Attachment> files = attachmentRepo.findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(orgId, "Whiteboard", id);
        attachmentRepo.deleteAll(files);
        boardRepo.deleteById(id);
        deleteBlobsAfterCommit(files);
        auditService.log("delete", "whiteboards", id, board.getName(), board, Map.of("items", items, "files", files.size()));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Items ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/items")
    @Transactional
    public ResponseEntity<?> addItem(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        WhiteboardBoard board = findBoard(actor, id);
        if (board == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy());
        if (body == null) throw new IllegalArgumentException("request body is required");
        String type = str(body, "type");
        if (type != null && !ITEM_TYPES.contains(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported item type"));
        }
        WhiteboardItem item = new WhiteboardItem();
        item.setBoardId(id);
        item.setOrganizationId(orgId);
        item.setType(type != null ? type : "sticky");
        item.setColor("connector".equals(item.getType()) ? "#64748b" : "#ffe066");
        applyItemFields(item, body);
        if (item.getW() <= 0) item.setW(200);
        if (item.getH() <= 0) item.setH(140);
        if ("connector".equals(item.getType())) {
            applyConnectorFields(item, body, true);
        }
        Long attachmentId = longVal(body, "attachmentId");
        if (attachmentId != null) {
            if (attachmentRepo.findByIdAndOrganizationId(attachmentId, orgId).isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "attachment not found"));
            }
            item.setAttachmentId(attachmentId);
        }
        item.setCreatedBy(actor.getId());
        Instant now = Instant.now();
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        itemRepo.save(item);
        board.setUpdatedAt(now);
        boardRepo.save(board);
        return ResponseEntity.status(201).body(item);
    }

    @PutMapping("/items/{itemId}")
    @Transactional
    public ResponseEntity<WhiteboardItem> updateItem(@PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        User actor = currentUser.get();
        WhiteboardItem item = itemRepo.findByIdAndOrganizationId(itemId, actor.getOrganizationId()).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        WhiteboardBoard board = findBoard(actor, item.getBoardId());
        if (board == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy());
        applyItemFields(item, body);
        if ("connector".equals(item.getType())) applyConnectorFields(item, body, false);
        item.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(itemRepo.save(item));
    }

    @DeleteMapping("/items/{itemId}")
    @Transactional
    public ResponseEntity<?> deleteItem(@PathVariable Long itemId) {
        User actor = currentUser.get();
        WhiteboardItem item = itemRepo.findByIdAndOrganizationId(itemId, actor.getOrganizationId()).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        WhiteboardBoard board = findBoard(actor, item.getBoardId());
        if (board == null) return ResponseEntity.notFound().build();
        // The item's author or the board's creator (any item for Manager+).
        if (!permissionService.can(actor, Action.DELETE, ResourceType.WHITEBOARD, item.getOrganizationId(), item.getCreatedBy())) {
            permissionService.require(actor, Action.DELETE, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy());
        }
        int connectors = itemRepo.deleteConnectorsOf(actor.getOrganizationId(), itemId);
        itemRepo.deleteById(itemId);
        return ResponseEntity.ok(Map.of("ok", true, "deletedConnectors", connectors));
    }

    /** Batch position/size save — called once at drag-end rather than per pixel. */
    @PostMapping("/{id}/items/bulk")
    @Transactional
    public ResponseEntity<?> bulkUpdate(@PathVariable Long id, @RequestBody List<Map<String, Object>> items) {
        User actor = currentUser.get();
        Long orgId = actor.getOrganizationId();
        WhiteboardBoard board = findBoard(actor, id);
        if (board == null) return ResponseEntity.notFound().build();
        permissionService.require(actor, Action.EDIT, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy());
        if (items == null) return ResponseEntity.badRequest().body(Map.of("error", "body must be a list of items"));
        if (items.size() > MAX_BULK_ITEMS) {
            return ResponseEntity.badRequest().body(Map.of("error", "at most " + MAX_BULK_ITEMS + " items per request"));
        }
        Map<Long, Map<String, Object>> patches = new LinkedHashMap<>();
        for (Map<String, Object> patch : items) {
            if (patch == null) continue;
            Long itemId = longVal(patch, "id");
            if (itemId != null) patches.put(itemId, patch);
        }
        if (patches.isEmpty()) return ResponseEntity.ok(Map.of("updated", 0));
        // One query for the whole batch, scoped to this board + org.
        List<WhiteboardItem> existing = itemRepo.findByBoardIdAndOrganizationIdAndIdIn(id, orgId, patches.keySet());
        Instant now = Instant.now();
        for (WhiteboardItem item : existing) {
            applyItemFields(item, patches.get(item.getId()));
            item.setUpdatedAt(now);
        }
        itemRepo.saveAll(existing);
        return ResponseEntity.ok(Map.of("updated", existing.size()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Org-scoped lookup + VIEW check; another org's board is a 404. */
    private WhiteboardBoard findBoard(User actor, Long id) {
        WhiteboardBoard board = boardRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (board == null) return null;
        permissionService.require(actor, Action.VIEW, ResourceType.WHITEBOARD, board.getOrganizationId(), board.getCreatedBy());
        return board;
    }

    /** Partial update: only keys actually present in the body are applied (absent x/y/rotation/zIndex are kept). */
    private void applyItemFields(WhiteboardItem item, Map<String, Object> body) {
        if (body == null) return;
        if (body.containsKey("x")) item.setX(num(body, "x"));
        if (body.containsKey("y")) item.setY(num(body, "y"));
        if (body.containsKey("w")) { double w = num(body, "w"); if (w > 0) item.setW(w); }
        if (body.containsKey("h")) { double h = num(body, "h"); if (h > 0) item.setH(h); }
        if (body.containsKey("rotation")) item.setRotation(num(body, "rotation"));
        if (body.containsKey("zIndex")) item.setZIndex((int) num(body, "zIndex"));
        if (body.containsKey("content")) {
            String content = str(body, "content");
            if (content != null && content.length() > 20_000) throw new IllegalArgumentException("content is too long");
            item.setContent(content);
        }
        String color = str(body, "color");
        if (color != null) item.setColor(color);
    }

    /** Validates/apply connector endpoints: both must be non-connector items of the same board. */
    private void applyConnectorFields(WhiteboardItem item, Map<String, Object> body, boolean creating) {
        if (body == null) return;
        Long from = body.containsKey("fromItemId") ? longVal(body, "fromItemId") : item.getFromItemId();
        Long to = body.containsKey("toItemId") ? longVal(body, "toItemId") : item.getToItemId();
        if (creating || body.containsKey("fromItemId") || body.containsKey("toItemId")) {
            if (from == null || to == null) throw new IllegalArgumentException("a connector needs fromItemId and toItemId");
            if (from.equals(to)) throw new IllegalArgumentException("a connector must join two different elements");
            List<WhiteboardItem> ends = itemRepo.findByBoardIdAndOrganizationIdAndIdIn(item.getBoardId(), item.getOrganizationId(), List.of(from, to));
            if (ends.size() != 2) throw new IllegalArgumentException("connector endpoints must be elements of this board");
            if (ends.stream().anyMatch(e -> "connector".equals(e.getType()))) {
                throw new IllegalArgumentException("a connector cannot attach to another connector");
            }
            item.setFromItemId(from);
            item.setToItemId(to);
        }
        if (body.containsKey("arrowhead") || creating) {
            String arrow = str(body, "arrowhead");
            if (arrow == null) arrow = creating ? "end" : item.getArrowhead();
            if (!ARROWHEADS.contains(arrow)) throw new IllegalArgumentException("arrowhead must be one of " + ARROWHEADS);
            item.setArrowhead(arrow);
        }
    }

    private void deleteBlobsAfterCommit(List<Attachment> files) {
        if (files.isEmpty()) return;
        Runnable cleanup = () -> files.forEach(a -> storageService.delete(a.getStorageBackend(), a.getStorageKey()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }

    private static double num(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        throw new IllegalArgumentException(key + " must be a number");
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw new IllegalArgumentException(key + " must be a string");
    }

    private static Long longVal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        throw new IllegalArgumentException(key + " must be a number");
    }
}
