package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.OrgSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OrgSettingsRepository extends JpaRepository<OrgSettings, Long> {
    Optional<OrgSettings> findByOrganizationId(Long organizationId);
}
