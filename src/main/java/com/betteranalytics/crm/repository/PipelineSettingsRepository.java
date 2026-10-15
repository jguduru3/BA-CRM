package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.PipelineSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PipelineSettingsRepository extends JpaRepository<PipelineSettings, Long> {
    Optional<PipelineSettings> findByOrganizationId(Long organizationId);
}
