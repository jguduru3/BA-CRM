package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Reminder;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.ReminderRepository;
import com.betteranalytics.crm.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * GET    /api/reminders         — list current user's reminders
 * POST   /api/reminders         — create
 * DELETE /api/reminders/{id}    — delete (own reminders only)
 *
 * Reminders fire automatically via ReminderSchedulerService every minute.
 * Reminders are personal: every operation is scoped to the calling user.
 */
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderRepository repo;
    private final CurrentUser currentUser;

    public ReminderController(ReminderRepository repo, CurrentUser currentUser) {
        this.repo = repo;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Reminder> list() {
        User actor = currentUser.get();
        return repo.findByOrganizationIdAndUserId(actor.getOrganizationId(), actor.getId());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Reminder body) {
        User actor = currentUser.get();
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        if (body.getRemindAt() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "remindAt is required"));
        }
        // Copy only client-controlled fields; owner/org/fired state are server-controlled.
        Reminder r = new Reminder();
        r.setOrganizationId(actor.getOrganizationId());
        r.setUserId(actor.getId());
        r.setTitle(body.getTitle());
        r.setBody(body.getBody());
        r.setRemindAt(body.getRemindAt());
        r.setRelatedType(body.getRelatedType() == null || body.getRelatedType().isBlank() ? null : body.getRelatedType());
        r.setRelatedId(r.getRelatedType() != null ? body.getRelatedId() : null);
        r.setFired(false);
        r.setFiredAt(null);
        r.setCreatedAt(Instant.now());
        return ResponseEntity.status(201).body(repo.save(r));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        User actor = currentUser.get();
        // Someone else's reminder is indistinguishable from a missing one.
        Reminder existing = repo.findByIdAndOrganizationIdAndUserId(id, actor.getOrganizationId(), actor.getId())
                .orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        repo.delete(existing);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
