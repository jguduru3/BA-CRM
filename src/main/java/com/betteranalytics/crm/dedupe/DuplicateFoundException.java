package com.betteranalytics.crm.dedupe;

import java.util.List;

/**
 * Raised when a duplicate rule stops a save. Rendered by {@link DuplicateExceptionHandler}
 * as 409 with code DUPLICATE_WARNING (bypassable) or DUPLICATE_BLOCKED, plus the candidates.
 * Extends IllegalStateException so that, should the dedicated handler ever be missing,
 * the global handler still answers 409.
 */
public class DuplicateFoundException extends IllegalStateException {

    private final boolean blocked;
    private final ObjectType type;
    private final transient List<DuplicateCandidate> candidates;

    public DuplicateFoundException(ObjectType type, boolean blocked, List<DuplicateCandidate> candidates) {
        super(blocked
                ? "A matching " + type.relatedType.toLowerCase() + " already exists and this organization does not allow duplicates."
                : "This " + type.relatedType.toLowerCase() + " looks like a duplicate of an existing record.");
        this.type = type;
        this.blocked = blocked;
        this.candidates = candidates;
    }

    public boolean isBlocked() { return blocked; }
    public ObjectType getType() { return type; }
    public List<DuplicateCandidate> getCandidates() { return candidates; }
    public String code() { return blocked ? "DUPLICATE_BLOCKED" : "DUPLICATE_WARNING"; }
}
