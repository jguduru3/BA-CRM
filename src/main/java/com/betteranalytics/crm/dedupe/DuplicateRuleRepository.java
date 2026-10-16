package com.betteranalytics.crm.dedupe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DuplicateRuleRepository extends JpaRepository<DuplicateRule, Long> {
    Optional<DuplicateRule> findByOrganizationIdAndObjectType(Long organizationId, ObjectType objectType);

    List<DuplicateRule> findByOrganizationId(Long organizationId);
}
