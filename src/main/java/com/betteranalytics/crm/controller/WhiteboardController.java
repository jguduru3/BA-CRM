package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.WhiteboardBoard;
import com.betteranalytics.crm.entity.WhiteboardItem;
import com.betteranalytics.crm.repository.AttachmentRepository;
import com.betteranalytics.crm.repository.WhiteboardBoardRepository;
import com.betteranalytics.crm.repository.WhiteboardItemRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whiteboards — a Jamboard/Miro-style freeform canvas for planning and
 * brainstorming, separate from the structured CRM/database objects.
 *
 * GET    /api/whiteboards                    — list boards
 * POST   /api/whiteboards                    — create board
 * GET    /api/whiteboards/{id}                — board + items
 * PUT    /api/whiteboards/{id}                — rename/describe board
 * DELETE /api/whiteboards/{id}                — delete board (items cascade)
 *
 * POST   /api/whiteboards/{id}/items          — add an item (sticky/text/shape/image/connector)
 * PUT    /api/whiteboards/items/{itemId}      — update one item (move/resize/edit/recolor)
 * DELETE /api/whiteboards/items/{itemId}      — remove an item
 * POST   /api/whiteboards/{id}/items/bulk     — batch position update (drag-end save)
 */
@RestController
@RequestMapping("/api/whiteboards")
public class WhiteboardController {

    private final WhiteboardBoardRepository boardRepo;
    private static final int MAX_BULK_ITEMS = 500;
    private static final Set<String> ITEM_TYPES = Set.of("sticky", "text", "shape", "image", "connector");

    private final WhiteboardItemRepository itemRepo;
    private final AttachmentRepository attachmentRepo;
    private final CurrentUser currentUser;
    private final AuditService auditService;

    public WhiteboardController(WhiteboardBoardRepository boardRepo, WhiteboardItemRepository itemRepo,
                                 AttachmentRepository attachmentRepo,
                                 CurrentUser currentUser, AuditService auditService) {
        this.boardRepo = boardRepo;
        this.itemRepo = itemRepo;
        this.attachmentRepo = attachmentRepo;
        this.currentUser = currentUser;
        this.auditService = auditService;
    }

    @GetMapping
    public List<WhiteboardBoard> list() {
        return boardRepo.findByOrganizationIdOrderByUpdatedAtDesc(currentUser.organizationId());
    }

    @PostMapping
    public ResponseEntity<WhiteboardBoard> create(@RequestBody WhiteboardBoard body) {
        User actor = currentUser.get();
        WhiteboardBoard board = new WhiteboardBoard();
        board.setOrganizationId(actor.getOrganizationId());
        board.setName(body.getName() != null && !body.getName().isBlank() ? body.getName() : "Untitled board");
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
        Long orgId = currentUser.organizationId();
        WhiteboardBoard board = boardRepo.findByIdAndOrganizationId(id, orgId).orElse(null);
        if (board == null) return ResponseEntity.notFound().build();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("board", board);
        result.put("items", itemRepo.findByBoardIdAndOrganizationId(id, orgId));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<WhiteboardBoard> update(@PathVariable Long id, @RequestBody WhiteboardBoard body) {
        User actor = currentUser.get();
        WhiteboardBoard board = boardRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (board == null) return ResponseEntity.notFound().build();
        requireBoardOwnerOrManager(actor, board);
        if (body.getName() != null && !body.getName().isBlank()) board.setName(body.getName());
        if (body.getDescription() != null) board.setDescription(body.getDescription());
        board.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(boardRepo.save(board));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        WhiteboardBoard board = boardRepo.findByIdAndOrganizationId(id, actor.getOrganizationId()).orElse(null);
        if (board == null) return ResponseEntity.notFound().build();
        requireBoardOwnerOrManager(actor, board);
        boardRepo.delete(board);
        auditService.log("delete", "whiteboards", id, board.getName(), board, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Items ────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/items")
    public ResponseEntity<?> addItem(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long orgId = currentUser.organizationId();
        WhiteboardBoard board = boardRepo.findByIdAndOrganizationId(id, orgId).orElse(null);
        if (board == null) return ResponseEntity.notFound().build();

        WhiteboardItem item = new WhiteboardItem();
        item.setBoardId(id);
        item.setOrganizationId(orgId);
        String type = str(body, "type");
        if (type != null && !ITEM_TYPES.contains(type)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported item type"));
        }
        item.setType(type != null ? type : "sticky");
        item.setColor("#ffe066");
        applyItemFields(item, body);
        if (item.getW() <= 0) item.setW(200);
        if (item.getH() <= 0) item.setH(140);

        Long attachmentId = longVal(body, "attachmentId");
        if (attachmentId != null) {
            if (attachmentRepo.findByIdAndOrganizationId(attachmentId, orgId).isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "attachment not found"));
            }
            item.setAttachmentId(attachmentId);
        }
        item.setCreatedBy(currentUser.userId());
        Instant now = Instant.now();
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        itemRepo.save(item);

        board.setUpdatedAt(now);
        boardRepo.save(board);
        return ResponseEntity.status(201).body(item);
    }

    @PutMapping("/items/{itemId}")
    public ResponseEntity<WhiteboardItem> updateItem(@PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        Long orgId = currentUser.organizationId();
        WhiteboardItem item = itemRepo.findByIdAndOrganizationId(itemId, orgId).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        applyItemFields(item, body);
        item.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(itemRepo.save(item));
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<?> deleteItem(@PathVariable Long itemId) {
        User actor = currentUser.get();
        WhiteboardItem item = itemRepo.findByIdAndOrganizationId(itemId, actor.getOrganizationId()).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        if (!actor.getId().equals(item.getCreatedBy()) && !isManagerPlus(actor)) {
            WhiteboardBoard board = boardRepo.findByIdAndOrganizationId(item.getBoardId(), actor.getOrganizationId()).orElse(null);
            if (board == null || !actor.getId().equals(board.getCreatedBy())) {
                throw new AccessDeniedException("only the item's author, the board owner or a manager can delete it");
            }
        }
        itemRepo.delete(item);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Batch position/size save — called once at drag-end rather than per pixel. */
    @PostMapping("/{id}/items/bulk")
    @Transactional
    public ResponseEntity<?> bulkUpdate(@PathVariable Long id, @RequestBody List<Map<String, Object>> items) {
        Long orgId = currentUser.organizationId();
        if (boardRepo.findByIdAndOrganizationId(id, orgId).isEmpty()) return ResponseEntity.notFound().build();
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

    /** Partial update: only keys actually present in the body are applied (absent x/y/rotation/zIndex are kept). */
    private void applyItemFields(WhiteboardItem item, Map<String, Object> body) {
        if (body == null) return;
        if (body.containsKey("x")) item.setX(num(body, "x"));
        if (body.containsKey("y")) item.setY(num(body, "y"));
        if (body.containsKey("w")) { double w = num(body, "w"); if (w > 0) item.setW(w); }
        if (body.containsKey("h")) { double h = num(body, "h"); if (h > 0) item.setH(h); }
        if (body.containsKey("rotation")) item.setRotation(num(body, "rotation"));
        if (body.containsKey("zIndex")) item.setZIndex((int) num(body, "zIndex"));
        if (body.containsKey("content")) item.setContent(str(body, "content"));
        String color = str(body, "color");
        if (color != null) item.setColor(color);
    }

    private void requireBoardOwnerOrManager(User actor, WhiteboardBoard board) {
        if (!actor.getId().equals(board.getCreatedBy()) && !isManagerPlus(actor)) {
            throw new AccessDeniedException("only the board owner or a manager can change this board");
        }
    }

    private static boolean isManagerPlus(User u) {
        return u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES || u.getRole() == Role.MANAGER;
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
