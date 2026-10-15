package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.CampaignAudience;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CampaignAudienceRepository extends JpaRepository<CampaignAudience, Long> {
    List<CampaignAudience> findByCampaignId(Long campaignId);
    List<CampaignAudience> findByCampaignIdAndOrganizationIdOrderByCreatedAtDesc(Long campaignId, Long organizationId);
    Optional<CampaignAudience> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<CampaignAudience> findByIdAndCampaignIdAndOrganizationId(Long id, Long campaignId, Long organizationId);
}
