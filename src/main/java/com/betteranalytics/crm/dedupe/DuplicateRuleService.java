package com.betteranalytics.crm.dedupe;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/** Reads duplicate rules, falling back to the default (enabled, WARN / WARN). */
@Service
public class DuplicateRuleService {

    private final DuplicateRuleRepository repository;

    public DuplicateRuleService(DuplicateRuleRepository repository) {
        this.repository = repository;
    }

    public DuplicateRule get(Long orgId, ObjectType type) {
        return repository.findByOrganizationIdAndObjectType(orgId, type)
                .orElseGet(() -> DuplicateRule.defaults(orgId, type));
    }

    /** One rule per object type, defaults included, in LEAD, CONTACT, COMPANY order. */
    public List<DuplicateRule> all(Long orgId) {
        return Arrays.stream(ObjectType.values()).map(t -> get(orgId, t)).toList();
    }
}
