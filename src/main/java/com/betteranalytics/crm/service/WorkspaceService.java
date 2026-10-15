package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.Workspace;
import com.betteranalytics.crm.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;

/**
 * Owns the "exactly one default workspace per organization" invariant.
 *
 * The service enforces it (first workspace becomes the default; promoting one
 * demotes the others; the default can only be unset/deleted when another
 * workspace is promoted in the same call) and the database backs it up with a
 * UNIQUE index on the default_org_id mirror column (V40), so even a racing or
 * buggy writer can never leave two defaults. Every change row-locks the org's
 * workspaces first, so concurrent default changes serialise.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository repo;

    public WorkspaceService(WorkspaceRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Workspace create(User actor, Workspace draft, boolean requestDefault) {
        Long orgId = actor.getOrganizationId();
        repo.lockAllByOrganizationId(orgId);
        boolean first = repo.countByOrganizationId(orgId) == 0;
        boolean makeDefault = first || requestDefault;
        Workspace w = new Workspace();
        w.setOrganizationId(orgId);
        w.setName(draft.getName().trim());
        w.setDescription(draft.getDescription());
        if (draft.getIcon() != null) w.setIcon(draft.getIcon());
        if (draft.getColor() != null) w.setColor(draft.getColor());
        w.setCreatedBy(actor.getId());
        w.setCreatedAt(Instant.now());
        w.setUpdatedAt(Instant.now());
        w.setDefault(false);
        Workspace saved = repo.saveAndFlush(w);
        if (makeDefault) return promote(orgId, saved.getId());
        return saved;
    }

    /**
     * Applies a default-flag change. {@code makeDefault == true} promotes this
     * workspace (demoting the current default). {@code makeDefault == false} on the
     * current default is only allowed with {@code replacementId} naming another
     * workspace of the org, which becomes the default in the same transaction.
     */
    @Transactional
    public Workspace changeDefault(Long orgId, Long workspaceId, boolean makeDefault, Long replacementId) {
        repo.lockAllByOrganizationId(orgId);
        Workspace w = find(orgId, workspaceId);
        if (makeDefault) return w.isDefault() ? w : promote(orgId, workspaceId);
        if (!w.isDefault()) return w;
        if (replacementId == null || replacementId.equals(workspaceId)) {
            throw new IllegalStateException("an organization must always have a default workspace — make another workspace the default first");
        }
        requireReplacement(orgId, replacementId);
        promote(orgId, replacementId);
        return find(orgId, workspaceId);
    }

    /** Deletes a workspace; the default may only go when {@code replacementId} is promoted in the same call. */
    @Transactional
    public Workspace delete(Long orgId, Long workspaceId, Long replacementId) {
        repo.lockAllByOrganizationId(orgId);
        Workspace w = find(orgId, workspaceId);
        if (repo.countTeamsInWorkspace(workspaceId) > 0) {
            throw new IllegalStateException("move this workspace's teams to another workspace before deleting it");
        }
        if (w.isDefault()) {
            if (replacementId == null || replacementId.equals(workspaceId)) {
                throw new IllegalStateException("the default workspace cannot be deleted — make another workspace the default first");
            }
            requireReplacement(orgId, replacementId);
            promote(orgId, replacementId);
            w = find(orgId, workspaceId);
        }
        repo.delete(w);
        repo.flush();
        return w;
    }

    private Workspace promote(Long orgId, Long id) {
        // Demote first (bulk UPDATE flushes and clears), so the UNIQUE index never sees two defaults.
        repo.clearOtherDefaults(orgId, id);
        Workspace w = find(orgId, id);
        w.setDefault(true);
        w.setUpdatedAt(Instant.now());
        return repo.saveAndFlush(w);
    }

    private void requireReplacement(Long orgId, Long id) {
        if (repo.findByIdAndOrganizationId(id, orgId).isEmpty()) {
            throw new IllegalArgumentException("newDefaultId is not a workspace of this organization");
        }
    }

    private Workspace find(Long orgId, Long id) {
        return repo.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new NoSuchElementException("workspace not found"));
    }
}
