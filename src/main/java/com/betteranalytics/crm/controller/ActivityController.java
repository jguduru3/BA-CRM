package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.core.ListParams;
import com.betteranalytics.crm.core.Specs;
import com.betteranalytics.crm.entity.Activity;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.permission.Action;
import com.betteranalytics.crm.permission.PermissionService;
import com.betteranalytics.crm.permission.PermissionService.OwnerScope;
import com.betteranalytics.crm.permission.ResourceType;
import com.betteranalytics.crm.repository.ActivityRepository;
import com.betteranalytics.crm.security.CurrentUser;
import com.betteranalytics.crm.service.AuditService;
import com.betteranalytics.crm.service.TenantGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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

    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "createdAt", "type", "type", "scheduledAt", "scheduledAt",
            "completedAt", "completedAt", "id", "id");

    /** Newest first by default; filtered, scoped and paginated in the database. */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String relatedType,
                                    @RequestParam(required = false) Long relatedId,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) Long userId,
                                    @RequestParam(defaultValue = "") String search,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "false") boolean withCorrections) {
        User actor = currentUser.get();
        OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.ACTIVITY);
        if (scope.none()) throw new AccessDeniedException("not permitted: " + actor.getRole() + " cannot VIEW activities");
        Specification<Activity> spec = Specs.<Activity>org(actor.getOrganizationId())
                .and(Specs.ownedWithin(scope, "userId"))
                .and(Specs.eqIgnoreCase("relatedType", relatedType))
                .and(Specs.eq("relatedId", relatedId))
                .and(Specs.eqIgnoreCase("type", type))
                .and(Specs.eq("userId", userId));
        if (search != null && !search.isBlank()) {
            spec = spec.and(Specs.search(search, "subject", "outcome"));
            // Subjects of other people's activities are "[restricted]" for reps, so their
            // search only looks at their own entries — otherwise it would be an oracle.
            Role r = actor.getRole();
            if (r != Role.SUPER_ADMIN && r != Role.VP_SALES && r != Role.MANAGER) {
                spec = spec.and(Specs.eq("userId", actor.getId()));
            }
        }
        Page<Activity> result = activityRepository.findAll(spec,
                ListParams.pageable(page, size, limit, sort, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt")));
        // V5: mask body/subject for entries the actor isn't allowed to read in full
        Map<String, Object> out = ListParams.response(result, permissionService.maskActivitiesForResponse(actor, result.getContent()));
        if (withCorrections) {
            // Two bounded queries so the timeline can show "corrected" entries next to their
            // originals: the corrections of originals on this page, and the originals of
            // corrections on this page — both within the reader's scope and masked.
            Specification<Activity> visible = Specs.<Activity>org(actor.getOrganizationId())
                    .and(Specs.ownedWithin(scope, "userId"));
            List<Long> ids = result.getContent().stream().map(Activity::getId).toList();
            List<Long> originalIds = result.getContent().stream()
                    .map(Activity::getCorrectionForId).filter(java.util.Objects::nonNull).distinct().toList();
            Map<Long, List<Activity>> corrections = new java.util.LinkedHashMap<>();
            if (!ids.isEmpty()) {
                activityRepository.findAll(visible.and(Specs.in("correctionForId", ids)), Sort.by(Sort.Direction.DESC, "createdAt"))
                        .forEach(c -> corrections.computeIfAbsent(c.getCorrectionForId(), k -> new java.util.ArrayList<>())
                                .add(permissionService.maskActivityForResponse(actor, c)));
            }
            Map<Long, Activity> originals = new java.util.LinkedHashMap<>();
            if (!originalIds.isEmpty()) {
                activityRepository.findAll(visible.and(Specs.in("id", originalIds)))
                        .forEach(o -> originals.put(o.getId(), permissionService.maskActivityForResponse(actor, o)));
            }
            out.put("corrections", corrections);
            out.put("originals", originals);
        }
        return out;
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
