package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.Activity;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ActivityRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Activity timeline — append-only per spec §4: nobody edits or deletes a
 * logged activity. Mistakes are fixed by adding a correction entry linked to
 * the original via POST /{id}/correct (V5).
 *
 * V5 adds:
 *   - Activity body masking: Managers and VPs see full content; reps see full
 *     content only for activities they authored; others get metadata only.
 *   - POST /{id}/correct: creates a new activity with correctionForId set,
 *     making the correction traceable in the timeline.
 */
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final ActivityRepository activityRepository;
    private final CurrentUser        currentUser;
    private final PermissionService  permissionService;
    private final AuditService       auditService;
    private final TenantGuard        tenantGuard;

    public ActivityController(ActivityRepository activityRepository, CurrentUser currentUser,
                               PermissionService permissionService, AuditService auditService,
                               TenantGuard tenantGuard) {
        this.activityRepository = activityRepository;
        this.currentUser        = currentUser;
        this.permissionService  = permissionService;
        this.auditService       = auditService;
        this.tenantGuard        = tenantGuard;
    }

    /** Newest first; rows with no createdAt (legacy imports) sink to the end instead of throwing. */
    private static final Comparator<Activity> NEWEST_FIRST =
            Comparator.comparing(Activity::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "200") int limit,
                                     @RequestParam(required = false) String relatedType,
                                     @RequestParam(required = false) Long relatedId) {
        User actor = currentUser.get();
        int max = TenantGuard.clampLimit(limit);
        List<Activity> all = activityRepository.findByOrganizationId(currentUser.organizationId())
                .stream()
                .filter(a -> permissionService.can(actor, Action.VIEW, ResourceType.ACTIVITY,
                                                   a.getOrganizationId(), a.getUserId()))
                .filter(a -> relatedType == null || relatedType.equalsIgnoreCase(a.getRelatedType()))
                .filter(a -> relatedId   == null || relatedId.equals(a.getRelatedId()))
                .collect(Collectors.toList());
        all.sort(NEWEST_FIRST);
        List<Activity> page = all.stream().limit(max).collect(Collectors.toList());
        // V5: mask body/subject for entries the actor isn't allowed to read in full
        return Map.of("count", all.size(),
                      "data",  permissionService.maskActivitiesForResponse(actor, page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Activity> get(@PathVariable Long id) {
        Activity activity = activityRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElse(null);
        if (activity == null) throw new NoSuchElementException("activity not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.ACTIVITY,
                                  activity.getOrganizationId(), activity.getUserId());
        return ResponseEntity.ok(permissionService.maskActivityForResponse(actor, activity));
    }

    /**
     * List all correction entries linked to a given original activity.
     * Useful for reading the full correction chain in the timeline.
     */
    @GetMapping("/{id}/corrections")
    public ResponseEntity<?> corrections(@PathVariable Long id) {
        Activity original = activityRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElse(null);
        if (original == null) throw new NoSuchElementException("activity not found");
        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.ACTIVITY,
                                  original.getOrganizationId(), original.getUserId());
        // M16: seeing the original doesn't automatically mean seeing every correction —
        // each correction is its own activity with its own author, so filter per item.
        List<Activity> corrections = activityRepository.findCorrectionsFor(currentUser.organizationId(), id)
                .stream()
                .filter(c -> permissionService.can(actor, Action.VIEW, ResourceType.ACTIVITY,
                                                   c.getOrganizationId(), c.getUserId()))
                .sorted(NEWEST_FIRST)
                .collect(Collectors.toList());
        return ResponseEntity.ok(permissionService.maskActivitiesForResponse(actor, corrections));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Activity body) {
        User actor = currentUser.get();
        permissionService.require(actor, Action.CREATE, ResourceType.ACTIVITY,
                                  currentUser.organizationId(), null);
        if (body.getType() == null || body.getType().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "type is required"));
        }
        // The related record must exist in this tenant (M1); store the canonical spelling.
        String relatedType = tenantGuard.requireRelated(body.getRelatedType(), body.getRelatedId());

        body.setId(null);
        body.setCorrectionForId(null); // corrections go through /{id}/correct only
        body.setOrganizationId(currentUser.organizationId());
        body.setRelatedType(relatedType);
        // H7: the author is always the caller. Letting the client pick userId would
        // let anyone log activities "as" someone else — and since body visibility is
        // keyed on authorship, also plant content into another user's timeline.
        body.setUserId(actor.getId());
        body.setCreatedAt(Instant.now());
        Activity saved = activityRepository.save(body);
        auditService.log("create", "activities", saved.getId(),
                         saved.getType() + " on " + saved.getRelatedType() + ":" + saved.getRelatedId(),
                         null, saved);
        return ResponseEntity.status(201).body(permissionService.maskActivityForResponse(actor, saved));
    }

    /**
     * V5 — Correction entry: the timeline is append-only, so a mistake is
     * fixed by adding a new activity that links to the original via
     * correctionForId. Both entries remain visible; the reader follows
     * the chain to see what was corrected and why.
     *
     * Anyone who can VIEW the original activity can log a correction on it
     * (they have record access). The correction itself obeys the same body-
     * masking rules as any other activity.
     */
    @PostMapping("/{id}/correct")
    public ResponseEntity<?> correct(@PathVariable Long id, @RequestBody Activity body) {
        Activity original = activityRepository.findByIdAndOrganizationId(id, currentUser.organizationId())
                .orElse(null);
        if (original == null) throw new NoSuchElementException("activity not found");

        User actor = currentUser.get();
        permissionService.require(actor, Action.VIEW, ResourceType.ACTIVITY,
                                  original.getOrganizationId(), original.getUserId());
        // H13: a correction is a new activity, so the caller must also be allowed to log one
        permissionService.require(actor, Action.CREATE, ResourceType.ACTIVITY,
                                  currentUser.organizationId(), null);

        if (original.getCorrectionForId() != null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "this activity is already a correction; correct the original instead",
                                 "originalId", original.getCorrectionForId()));
        }

        Activity correction = new Activity();
        correction.setOrganizationId(currentUser.organizationId());
        correction.setType(body.getType() != null ? body.getType() : original.getType());
        correction.setSubject(body.getSubject());
        correction.setBody(body.getBody());
        correction.setUserId(currentUser.userId());
        correction.setRelatedType(original.getRelatedType());
        correction.setRelatedId(original.getRelatedId());
        correction.setOutcome(body.getOutcome());
        correction.setDurationMinutes(body.getDurationMinutes());
        correction.setCorrectionForId(original.getId()); // key link
        correction.setCreatedAt(Instant.now());
        Activity saved = activityRepository.save(correction);

        auditService.log("create", "activities", saved.getId(),
                         "correction for activity:" + original.getId(), null, saved);
        return ResponseEntity.status(201).body(permissionService.maskActivityForResponse(actor, saved));
    }
}
