package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.CampaignContact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CampaignContactRepository extends JpaRepository<CampaignContact, Long> {
    List<CampaignContact> findByCampaignId(Long campaignId);
    List<CampaignContact> findByCampaignIdAndOrganizationIdOrderByIdAsc(Long campaignId, Long organizationId, Pageable pageable);
    List<CampaignContact> findByCampaignIdAndStatus(Long campaignId, String status);
    List<CampaignContact> findByCampaignIdAndStatusOrderByIdAsc(Long campaignId, String status, Pageable pageable);
    Optional<CampaignContact> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<CampaignContact> findFirstByWaMessageIdAndOrganizationId(String waMessageId, Long organizationId);

    @Query("SELECT COUNT(cc) FROM CampaignContact cc WHERE cc.campaignId = :campaignId AND cc.status = :status")
    long countByCampaignIdAndStatus(@Param("campaignId") Long campaignId, @Param("status") String status);

    @Query("SELECT COUNT(cc) FROM CampaignContact cc WHERE cc.campaignId = :campaignId AND cc.status IN :statuses")
    long countByCampaignIdAndStatusIn(@Param("campaignId") Long campaignId, @Param("statuses") Collection<String> statuses);

    long countByCampaignId(Long campaignId);

    @Query("SELECT cc.phoneNumber FROM CampaignContact cc WHERE cc.campaignId = :campaignId")
    List<String> findPhoneNumbersByCampaignId(@Param("campaignId") Long campaignId);

    /**
     * Atomically claim a pending recipient for sending. Returns 1 only for the
     * one dispatcher that wins — prevents double sends when a resume/scheduler
     * dispatch overlaps an in-flight one.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CampaignContact cc SET cc.status = 'sending' WHERE cc.id = :id AND cc.status = 'pending'")
    int claimForSending(@Param("id") Long id);
}
