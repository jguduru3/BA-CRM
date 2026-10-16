package com.betteranalytics.crm.dedupe;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * A possible duplicate found by the matching rules.
 *
 * @param matchedOn which keys matched: email, phone, domain, name, company
 * @param score     0..1 confidence (1 = exact email or domain)
 */
public record DuplicateCandidate(Long id, String name, List<String> matchedOn, double score,
                                 @JsonIgnore Long ownerId) {
}
