package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    java.util.Optional<Organization> findBySlug(String slug);
}
