package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.CampaignContact;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Campaign recipients double as per-recipient send records. Every state change
 * after creation is a conditional (compare-and-set) UPDATE in its own short
 * transaction, so the dispatcher, the stale-claim reset and the status webhook
 * can never overwrite each other or send the same recipient twice.
 */
public interface CampaignContactRepository extends JpaRepository<CampaignContact, Long> {
    List<CampaignContact> findByCampaignId(Long campaignId);
    List<CampaignContact> findByCampaignIdAndOrganizationIdOrderByIdAsc(Long campaignId, Long organizationId, Pageable pageable);
    List<CampaignContact> findByCampaignIdAndOrganizationIdAndStatusOrderByIdAsc(Long campaignId, Long organizationId, String status, Pageable pageable);
    List<CampaignContact> findByCampaignIdAndStatus(Long campaignId, String status);
    List<CampaignContact> findByCampaignIdAndStatusOrderByIdAsc(Long campaignId, String status, Pageable pageable);
    Optional<CampaignContact> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<CampaignContact> findFirstByWaMessageIdAndOrganizationId(String waMessageId, Long organizationId);

    @Query("SELECT COUNT(cc) FROM CampaignContact cc WHERE cc.campaignId = :campaignId AND cc.status = :status")
    long countByCampaignIdAndStatus(@Param("campaignId") Long campaignId, @Param("status") String status);

    @Query("SELECT COUNT(cc) FROM CampaignContact cc WHERE cc.campaignId = :campaignId AND cc.status IN :statuses")
    long countByCampaignIdAndStatusIn(@Param("campaignId") Long campaignId, @Param("statuses") Collection<String> statuses);

    long countByCampaignId(Long campaignId);

    /** Recipient counts per status in one grouped query: rows of [status, count]. */
    @Query("SELECT cc.status, COUNT(cc) FROM CampaignContact cc WHERE cc.campaignId = :campaignId GROUP BY cc.status")
    List<Object[]> countByStatus(@Param("campaignId") Long campaignId);

    @Query("SELECT cc.phoneNumber FROM CampaignContact cc WHERE cc.campaignId = :campaignId")
    List<String> findPhoneNumbersByCampaignId(@Param("campaignId") Long campaignId);

    /** Which of these numbers are already recipients of the campaign (dedupe on add, indexed). */
    @Query("SELECT cc.normalizedPhone FROM CampaignContact cc WHERE cc.campaignId = :campaignId " +
           "AND cc.normalizedPhone IN :phones")
    List<String> findExistingPhones(@Param("campaignId") Long campaignId, @Param("phones") Collection<String> phones);

    /** Which of these (lowercase) addresses are already recipients of the campaign. */
    @Query("SELECT cc.emailNormalized FROM CampaignContact cc WHERE cc.campaignId = :campaignId " +
           "AND cc.emailNormalized IN :emails")
    List<String> findExistingEmails(@Param("campaignId") Long campaignId, @Param("emails") Collection<String> emails);

    /** Organizations that sent an SMS campaign to this number (inbound SMS STOP mapping). */
    @Query("SELECT DISTINCT cc.organizationId FROM CampaignContact cc, Campaign c WHERE c.id = cc.campaignId " +
           "AND c.type = 'sms' AND cc.normalizedPhone = :phone")
    List<Long> findSmsOrganizationsByPhone(@Param("phone") String normalizedPhone);

    @Query("SELECT cc.id FROM CampaignContact cc WHERE cc.campaignId = :campaignId AND cc.status = 'pending' ORDER BY cc.id")
    List<Long> findPendingIds(@Param("campaignId") Long campaignId, Pageable pageable);

    /**
     * Atomically claim one pending recipient (portable fallback for H2; PostgreSQL
     * claims whole batches with FOR UPDATE SKIP LOCKED in CampaignDispatcher).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CampaignContact cc SET cc.status = 'sending', cc.claimedAt = :now, cc.updatedAt = :now " +
           "WHERE cc.id = :id AND cc.status = 'pending'")
    int claimForSending(@Param("id") Long id, @Param("now") Instant now);

    /** Records the send result — only for a row this worker still holds ('sending'). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CampaignContact cc SET cc.status = :status, cc.waMessageId = :waId, cc.sentAt = :now, " +
           "cc.failedReason = :reason, cc.updatedAt = :now WHERE cc.id = :id AND cc.status = 'sending'")
    int markResult(@Param("id") Long id, @Param("status") String status, @Param("waId") String waMessageId,
                   @Param("reason") String failedReason, @Param("now") Instant now);

    /** Hands claimed-but-unprocessed recipients back (campaign paused/cancelled mid-batch). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CampaignContact cc SET cc.status = 'pending', cc.claimedAt = NULL " +
           "WHERE cc.id IN :ids AND cc.status = 'sending'")
    int releaseClaims(@Param("ids") Collection<Long> ids);

    /** 'sending' claims older than the cutoff belong to a crashed worker → back to pending. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CampaignContact cc SET cc.status = 'pending', cc.claimedAt = NULL, cc.updatedAt = :now " +
           "WHERE cc.status = 'sending' AND (cc.claimedAt IS NULL OR cc.claimedAt < :cutoff)")
    int resetStaleClaims(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    /** Delivery webhook: move forward only from the status we read (compare-and-set). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CampaignContact cc SET cc.status = :next, " +
           "cc.deliveredAt = COALESCE(cc.deliveredAt, :deliveredAt), cc.readAt = COALESCE(cc.readAt, :readAt), " +
           "cc.failedReason = COALESCE(:reason, cc.failedReason), cc.updatedAt = :now " +
           "WHERE cc.id = :id AND cc.status = :current")
    int advanceDeliveryStatus(@Param("id") Long id, @Param("current") String current, @Param("next") String next,
                              @Param("deliveredAt") Instant deliveredAt, @Param("readAt") Instant readAt,
                              @Param("reason") String reason, @Param("now") Instant now);
}
