package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.TodoItem;
import com.betteranalytics.crm.entity.TodoList;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.TodoItemRepository;
import com.betteranalytics.crm.repository.TodoListRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lists:
 *   GET    /api/todos/lists
 *   POST   /api/todos/lists
 *   PUT    /api/todos/lists/{id}
 *   DELETE /api/todos/lists/{id}
 *
 * Items:
 *   GET    /api/todos/lists/{listId}/items
 *   POST   /api/todos/lists/{listId}/items
 *   PUT    /api/todos/items/{id}
 *   DELETE /api/todos/items/{id}
 *   POST   /api/todos/items/{id}/toggle     — toggle done/undone
 *
 * Visibility: a list is visible to its owner, and to everyone in the org when
 * it is shared. Only the owner may rename/delete a private list; a shared list
 * can also be managed by a Manager+. Items follow their list's visibility.
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");

    private final TodoListRepository listRepo;
    private final TodoItemRepository itemRepo;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public TodoController(TodoListRepository listRepo, TodoItemRepository itemRepo,
                          CurrentUser currentUser, ObjectMapper objectMapper) {
        this.listRepo = listRepo;
        this.itemRepo = itemRepo;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    // ── Lists ─────────────────────────────────────────────────────────────────

    @GetMapping("/lists")
    public List<TodoList> listLists() {
        User actor = currentUser.get();
        return listRepo.findVisibleByOrgAndUser(actor.getOrganizationId(), actor.getId());
    }

    @PostMapping("/lists")
    public ResponseEntity<?> createList(@RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        TodoList body = bind(raw, TodoList.class);
        if (body.getName() == null || body.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        TodoList list = new TodoList();
        list.setOrganizationId(actor.getOrganizationId());
        list.setOwnerId(actor.getId());
        list.setName(body.getName());
        list.setDescription(body.getDescription());
        list.setColor(body.getColor());
        list.setIcon(body.getIcon());
        list.setShared(body.isShared());
        list.setPosition(body.getPosition());
        list.setCreatedAt(Instant.now());
        list.setUpdatedAt(Instant.now());
        return ResponseEntity.status(201).body(listRepo.save(list));
    }

    @PutMapping("/lists/{id}")
    public ResponseEntity<TodoList> updateList(@PathVariable Long id, @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        TodoList existing = findVisibleList(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanManageList(actor, existing);
        TodoList body = bind(raw, TodoList.class);
        if (body.getName() != null && !body.getName().isBlank()) existing.setName(body.getName());
        if (body.getDescription() != null) existing.setDescription(body.getDescription());
        if (body.getColor()       != null) existing.setColor(body.getColor());
        if (body.getIcon()        != null) existing.setIcon(body.getIcon());
        // Only the owner may change who can see the list.
        if (raw.containsKey("shared") && actor.getId().equals(existing.getOwnerId())) existing.setShared(body.isShared());
        if (raw.containsKey("position")) existing.setPosition(body.getPosition());
        existing.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(listRepo.save(existing));
    }

    @DeleteMapping("/lists/{id}")
    public ResponseEntity<?> deleteList(@PathVariable Long id) {
        User actor = currentUser.get();
        TodoList existing = findVisibleList(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        requireCanManageList(actor, existing);
        listRepo.delete(existing); // items cascade (todo_items.list_id ON DELETE CASCADE)
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    @GetMapping("/lists/{listId}/items")
    public ResponseEntity<List<TodoItem>> listItems(@PathVariable Long listId) {
        User actor = currentUser.get();
        TodoList list = findVisibleList(actor, listId);
        if (list == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(itemRepo.findByListIdAndParentIdIsNullOrderByPositionAsc(list.getId()));
    }

    @PostMapping("/lists/{listId}/items")
    public ResponseEntity<?> createItem(@PathVariable Long listId,
                                        @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        TodoList list = findVisibleList(actor, listId);
        if (list == null) return ResponseEntity.notFound().build();
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
        item.setTitle(body.getTitle());
        item.setNotes(body.getNotes());
        item.setPriority(body.getPriority() != null ? body.getPriority() : "normal");
        item.setDueAt(body.getDueAt());
        item.setPosition(body.getPosition());
        item.setPinned(body.isPinned());
        item.setCreatedBy(actor.getId());
        item.setCreatedAt(Instant.now());
        item.setUpdatedAt(Instant.now());
        return ResponseEntity.status(201).body(itemRepo.save(item));
    }

    @PutMapping("/items/{id}")
    public ResponseEntity<?> updateItem(@PathVariable Long id,
                                        @RequestBody Map<String, Object> raw) {
        User actor = currentUser.get();
        TodoItem existing = findAccessibleItem(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        TodoItem body = bind(raw, TodoItem.class);
        if (body.getTitle() != null && !body.getTitle().isBlank()) existing.setTitle(body.getTitle());
        if (raw.containsKey("notes"))    existing.setNotes(body.getNotes());
        if (body.getPriority() != null) {
            if (!PRIORITIES.contains(body.getPriority())) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid priority"));
            }
            existing.setPriority(body.getPriority());
        }
        if (raw.containsKey("dueAt"))    existing.setDueAt(body.getDueAt());
        if (raw.containsKey("position")) existing.setPosition(body.getPosition());
        if (raw.containsKey("pinned"))   existing.setPinned(body.isPinned());
        existing.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(itemRepo.save(existing));
    }

    @PostMapping("/items/{id}/toggle")
    public ResponseEntity<TodoItem> toggle(@PathVariable Long id) {
        User actor = currentUser.get();
        TodoItem existing = findAccessibleItem(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        boolean nowDone = !existing.isDone();
        existing.setDone(nowDone);
        existing.setDoneAt(nowDone ? Instant.now() : null);
        existing.setUpdatedAt(Instant.now());
        return ResponseEntity.ok(itemRepo.save(existing));
    }

    @DeleteMapping("/items/{id}")
    @Transactional
    public ResponseEntity<?> deleteItem(@PathVariable Long id) {
        User actor = currentUser.get();
        TodoItem existing = findAccessibleItem(actor, id);
        if (existing == null) return ResponseEntity.notFound().build();
        boolean listOwner = existing.getListId() != null && listRepo.findByIdAndOrganizationId(existing.getListId(), actor.getOrganizationId())
                .map(l -> actor.getId().equals(l.getOwnerId())).orElse(false);
        if (!actor.getId().equals(existing.getCreatedBy()) && !listOwner && !isManagerPlus(actor)) {
            throw new AccessDeniedException("only the item's author, the list owner or a manager can delete it");
        }
        deleteWithChildren(existing, 0);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** parent_id has no ON DELETE action, so sub-tasks go first. */
    private void deleteWithChildren(TodoItem item, int depth) {
        if (depth < 20) {
            for (TodoItem child : itemRepo.findByParentIdOrderByPositionAsc(item.getId())) {
                if (Objects.equals(child.getOrganizationId(), item.getOrganizationId())) deleteWithChildren(child, depth + 1);
            }
        }
        itemRepo.delete(item);
    }

    private TodoList findVisibleList(User actor, Long listId) {
        if (listId == null) return null;
        TodoList list = listRepo.findByIdAndOrganizationId(listId, actor.getOrganizationId()).orElse(null);
        if (list == null) return null;
        // Private lists are invisible (404, not 403) to everyone but their owner.
        if (!list.isShared() && !actor.getId().equals(list.getOwnerId())) return null;
        return list;
    }

    /** An item is accessible when its list is visible; list-less items only to their creator. */
    private TodoItem findAccessibleItem(User actor, Long itemId) {
        TodoItem item = itemRepo.findByIdAndOrganizationId(itemId, actor.getOrganizationId()).orElse(null);
        if (item == null) return null;
        if (item.getListId() == null) return actor.getId().equals(item.getCreatedBy()) ? item : null;
        return findVisibleList(actor, item.getListId()) != null ? item : null;
    }

    private void requireCanManageList(User actor, TodoList list) {
        boolean owner = actor.getId().equals(list.getOwnerId());
        if (!owner && !(list.isShared() && isManagerPlus(actor))) {
            throw new AccessDeniedException("only the list owner can change this list");
        }
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
