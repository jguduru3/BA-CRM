package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.TodoItem;
import com.betteranalytics.crm.entity.TodoList;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.TodoItemRepository;
import com.betteranalytics.crm.repository.TodoListRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.Paging;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Lists:
 *   GET    /api/todos/lists                 ?page=&size=
 *   POST   /api/todos/lists
 *   PUT    /api/todos/lists/{id}
 *   DELETE /api/todos/lists/{id}            — items (and their sub-items) go with it
 *
 * Items:
 *   GET    /api/todos/lists/{listId}/items  ?done=&page=&size=  (top-level items)
 *   GET    /api/todos/items/{id}/subitems
 *   POST   /api/todos/lists/{listId}/items
 *   PUT    /api/todos/items/{id}            — partial update: only the keys sent change
 *   DELETE /api/todos/items/{id}            — sub-items are deleted with it
 *   POST   /api/todos/items/{id}/toggle     — toggle done/undone
 *
 * Access: PermissionService (tenant → TODO feature → OWN record scope on the
 * list owner). Sharing is layered on top: a shared list is visible to — and
 * its items can be worked on by — everyone in the org who has the TODO edit
 * feature; Manager+ may also rename/delete a shared list. SUPPORT is read-only.
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final int MAX_DEPTH = 20;

    private final TodoListRepository listRepo;
    private final TodoItemRepository itemRepo;
    private final CurrentUser currentUser;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public TodoController(TodoListRepository listRepo, TodoItemRepository itemRepo,
                          CurrentUser currentUser, PermissionService permissionService,
                          AuditService auditService, ObjectMapper objectMapper) {
        this.listRepo = listRepo;
        this.itemRepo = itemRepo;
        this.currentUser = currentUser;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ── Lists ─────────────────────────────────────────────────────────────────

    @GetMapping("/lists")
    public List<TodoList> listLists(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.TODO, actor.getOrganizationId(), actor.getId());
        return listRepo.findVisibleByOrgAndUser(actor.getOrganizationId(), actor.getId(),
                Paging.of(page, size, 500, 500, null));
    }

    @PostMapping("/lists")
    @Transactional
    public ResponseEntity<?> createList(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.TODO, actor.getOrganizationId(), null);
        TodoList body = bind(raw, TodoList.class);
        if (body.getName() == null || body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        TodoList list = new TodoList();
        list.setOrganizationId(actor.getOrganizationId());
        list.setOwnerId(actor.getId());
        list.setName(body.getName().trim());
        list.setDescription(body.getDescription());
        list.setColor(body.getColor());
        list.setIcon(body.getIcon());
        list.setShared(body.isShared());
        list.setPosition(body.getPosition());
        list.setCreatedAt(Instant.now());
        list.setUpdatedAt(Instant.now());
        TodoList saved = listRepo.save(list);
        auditService.log("create", "todo_lists", saved.getId(), saved.getName(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/lists/{id}")
    @Transactional
    public ResponseEntity<TodoList> updateList(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        TodoList existing = findVisibleList(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanManageList(actor, existing, Action.EDIT);
        TodoList body = bind(raw, TodoList.class);
        if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName().trim());
        if (raw.containsKey("description")) existing.setDescription(body.getDescription());
        if (raw.containsKey("color"))       existing.setColor(body.getColor());
        if (raw.containsKey("icon"))        existing.setIcon(body.getIcon());
        // Only the owner may change who can see the list.
        if (raw.containsKey("shared") && actor.getId().equals(existing.getOwnerId())) existing.setShared(body.isShared());
        if (raw.containsKey("position")) existing.setPosition(body.getPosition());
        existing.setUpdatedAt(Instant.now());
        TodoList saved = listRepo.save(existing);
        auditService.log("update", "todo_lists", id, saved.getName(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/lists/{id}")
    @Transactional
    public ResponseEntity<?> deleteList(@PathVariable Long id) {
        User actor = currentUser.get();
        TodoList existing = findVisibleList(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanManageList(actor, existing, Action.DELETE);
        // todo_items.parent_id has no ON DELETE action: detach sub-items first so the
        // list_id ON DELETE CASCADE can remove every item without an FK violation.
        itemRepo.detachParentsInList(existing.getId());
        listRepo.delete(existing);
        auditService.log("delete", "todo_lists", id, existing.getName(), existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    @GetMapping("/lists/{listId}/items")
    public ResponseEntity<List<TodoItem>> listItems(@PathVariable Long listId,
                                                    @RequestParam(required = false) Boolean done,
                                                    @RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size) {
        User actor = currentUser.get();
        TodoList list = findVisibleList(actor, listId);
        if (list == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(itemRepo.findTopLevel(list.getId(), done, Paging.of(page, size, 1000, 1000, null)));
    }

    @GetMapping("/items/{id}/subitems")
    public ResponseEntity<List<TodoItem>> listSubItems(@PathVariable Long id) {
        User actor = currentUser.get();
        TodoItem parent = findAccessibleItem(actor, id);
        if (parent == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(itemRepo.findByParentIdOrderByPositionAsc(parent.getId()));
    }

    @PostMapping("/lists/{listId}/items")
    @Transactional
    public ResponseEntity<?> createItem(@PathVariable Long listId,
                                        @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        TodoList list = findVisibleList(actor, listId);
        if (list == null) return ResponseEntity.notFound().build();
        requireCanWorkOnItems(actor, list);
        TodoItem body = bind(raw, TodoItem.class);
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        if (body.getPriority() != null && !PRIORITIES.contains(body.getPriority())) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid priority"));
        }
        if (body.getParentId() != null) {
            TodoItem parent = itemRepo.findByIdAndOrganizationId(body.getParentId(), actor.getOrganizationId()).orElse(null);
            if (parent == null || !Objects.equals(parent.getListId(), list.getId())) {
                return ResponseEntity.badRequest().body(Map.of("error", "parent item not found in this list"));
            }
        }
        TodoItem item = new TodoItem();
        item.setOrganizationId(actor.getOrganizationId());
        item.setListId(list.getId());
        item.setParentId(body.getParentId());
        item.setTitle(body.getTitle().trim());
        item.setNotes(body.getNotes());
        item.setPriority(body.getPriority() != null ? body.getPriority() : "normal");
        item.setDueAt(body.getDueAt());
        item.setPosition(body.getPosition());
        item.setPinned(body.isPinned());
        item.setCreatedBy(actor.getId());
        item.setCreatedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        TodoItem saved = itemRepo.save(item);
        auditService.log("create", "todo_items", saved.getId(), saved.getTitle(), null, saved);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/items/{id}")
    @Transactional
    public ResponseEntity<?> updateItem(@PathVariable Long id,
                                        @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        TodoItem existing = findAccessibleItem(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanWorkOnItem(actor, existing);
        TodoItem body = bind(raw, TodoItem.class);
        if (body.getTitle() != null && !body.getTitle().isBlank()) existing.setTitle(body.getTitle().trim());
        if (raw.containsKey("notes"))    existing.setNotes(body.getNotes());
        if (raw.containsKey("priority") && body.getPriority() != null) {
            if (!PRIORITIES.contains(body.getPriority())) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid priority"));
            }
            existing.setPriority(body.getPriority());
        }
        if (raw.containsKey("dueAt"))    existing.setDueAt(body.getDueAt());
        if (raw.containsKey("position")) existing.setPosition(body.getPosition());
        if (raw.containsKey("pinned"))   existing.setPinned(body.isPinned());
        if (raw.containsKey("done") && body.isDone() != existing.isDone()) {
            existing.setDone(body.isDone());
            existing.setDoneAt(body.isDone() ? Instant.now() : null);
        }
        existing.setUpdatedAt(Instant.now());
        TodoItem saved = itemRepo.save(existing);
        auditService.log("update", "todo_items", id, saved.getTitle(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/items/{id}/toggle")
    @Transactional
    public ResponseEntity<TodoItem> toggle(@PathVariable Long id) {
        User actor = currentUser.get();
        TodoItem existing = findAccessibleItem(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanWorkOnItem(actor, existing);
        boolean nowDone = !existing.isDone();
        existing.setDone(nowDone);
        existing.setDoneAt(nowDone ? Instant.now() : null);
        existing.setUpdatedAt(Instant.now());
        TodoItem saved = itemRepo.save(existing);
        auditService.log("update", "todo_items", id, saved.getTitle(), null, saved);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/items/{id}")
    @Transactional
    public ResponseEntity<?> deleteItem(@PathVariable Long id) {
        User actor = currentUser.get();
        TodoItem existing = findAccessibleItem(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        TodoList list = existing.getListId() == null ? null
                : listRepo.findByIdAndOrganizationId(existing.getListId(), actor.getOrganizationId()).orElse(null);
        Long listOwner = list != null ? list.getOwnerId() : existing.getCreatedBy();
        // Feature + OWN scope against the list owner; the item's author and Manager+
        // (on shared lists) are layered on top.
        boolean allowed = permissionService.can(actor, Action.DELETE, ResourceType.TODO, existing.getOrganizationId(), listOwner)
                || (hasFeature(actor, Action.DELETE)
                    && (actor.getId().equals(existing.getCreatedBy()) || (list != null && list.isShared() && isManagerPlus(actor))));
        if (!allowed) {
            throw new AccessDeniedException("only the item's author, the list owner or a manager can delete it");
        }
        deleteWithChildren(existing);
        auditService.log("delete", "todo_items", id, existing.getTitle(), existing, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * parent_id has no ON DELETE action, so descendants are collected level by
     * level (one query per level, bounded depth) and deleted deepest-first.
     */
    private void deleteWithChildren(TodoItem item) {
        List<List<Long>> levels = new ArrayList<>();
        List<Long> frontier = List.of(item.getId());
        for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<Long> children = itemRepo.findIdsByParentIdIn(frontier);
            if (children.isEmpty()) break;
            levels.add(children);
            frontier = children;
        }
        for (int i = levels.size() - 1; i >= 0; i--) itemRepo.deleteByIdIn(levels.get(i));
        itemRepo.deleteByIdIn(List.of(item.getId()));
    }

    private TodoList findVisibleList(User actor, Long listId) {
        if (listId == null) return null;
        TodoList list = listRepo.findByIdAndOrganizationId(listId, actor.getOrganizationId()).orElse(null);
        if (list == null) return null;
        // Private lists are invisible (404, not 403) to everyone but their owner.
        boolean visible = permissionService.can(actor, Action.VIEW, ResourceType.TODO, list.getOrganizationId(), list.getOwnerId())
                || (list.isShared() && hasFeature(actor, Action.VIEW));
        return visible ? list : null;
    }

    /** An item is accessible when its list is visible; list-less items only to their creator. */
    private TodoItem findAccessibleItem(User actor, Long itemId) {
        TodoItem item = itemRepo.findByIdAndOrganizationId(itemId, actor.getOrganizationId()).orElse(null);
        if (item == null) return null;
        if (item.getListId() == null) return actor.getId().equals(item.getCreatedBy()) ? item : null;
        return findVisibleList(actor, item.getListId()) != null ? item : null;
    }

    /** Rename/re-share/delete a list: OWN scope on the owner, or Manager+ on a shared list. */
    private void requireCanManageList(User actor, TodoList list, Action action) {
        if (permissionService.can(actor, action, ResourceType.TODO, list.getOrganizationId(), list.getOwnerId())) return;
        if (list.isShared() && isManagerPlus(actor) && hasFeature(actor, action)) return;
        permissionService.require(actor, action, ResourceType.TODO, list.getOrganizationId(), list.getOwnerId());
    }

    /** Add/edit/tick items: the list owner, or anyone with the TODO edit feature on a shared list. */
    private void requireCanWorkOnItems(User actor, TodoList list) {
        if (permissionService.can(actor, Action.EDIT, ResourceType.TODO, list.getOrganizationId(), list.getOwnerId())) return;
        if (list.isShared() && hasFeature(actor, Action.EDIT)) return;
        permissionService.require(actor, Action.EDIT, ResourceType.TODO, list.getOrganizationId(), list.getOwnerId());
    }

    private void requireCanWorkOnItem(User actor, TodoItem item) {
        if (item.getListId() == null) {
            permissionService.require(actor, Action.EDIT, ResourceType.TODO, item.getOrganizationId(), item.getCreatedBy());
            return;
        }
        TodoList list = listRepo.findByIdAndOrganizationId(item.getListId(), actor.getOrganizationId())
                .orElseThrow(() -> new NoSuchElementException("list not found"));
        requireCanWorkOnItems(actor, list);
    }

    /** True when the role has the feature at any scope (own records always fall inside every scope). */
    private boolean hasFeature(User actor, Action action) {
        return permissionService.can(actor, action, ResourceType.TODO, actor.getOrganizationId(), actor.getId());
    }

    private static boolean isManagerPlus(User u) {
        return u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES || u.getRole() == Role.MANAGER;
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
