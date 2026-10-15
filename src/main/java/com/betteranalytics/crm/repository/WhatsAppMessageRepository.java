package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WhatsAppMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, Long> {

    // Conversation thread for a linked CRM record
    List<WhatsAppMessage> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
            Long organizationId, String relatedType, Long relatedId);

    List<WhatsAppMessage> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
            Long organizationId, String relatedType, Long relatedId, Pageable pageable);

    /** Thread by counterparty number — indexed (organization_id, normalized_phone, created_at). */
    List<WhatsAppMessage> findByOrganizationIdAndNormalizedPhoneOrderByCreatedAtDesc(
            Long organizationId, String normalizedPhone, Pageable pageable);

    // Legacy: matches raw from/to columns (pre-V30 callers)
    @Query("SELECT m FROM WhatsAppMessage m WHERE m.organizationId = :orgId " +
           "AND (m.fromNumber = :phone OR m.toNumber = :phone) ORDER BY m.createdAt DESC")
    List<WhatsAppMessage> findByOrganizationIdAndPhoneOrderByCreatedAtDesc(
            @Param("orgId") Long organizationId, @Param("phone") String phone, Pageable pageable);

    /** Inbox page: rows of [normalizedPhone, lastMessageAt, messageCount], newest conversation first. */
    @Query("SELECT m.normalizedPhone, MAX(m.createdAt), COUNT(m) FROM WhatsAppMessage m " +
           "WHERE m.organizationId = :orgId AND m.normalizedPhone IS NOT NULL " +
           "GROUP BY m.normalizedPhone ORDER BY MAX(m.createdAt) DESC")
    List<Object[]> findThreads(@Param("orgId") Long organizationId, Pageable pageable);

    /** The newest message of each of the given threads (ties resolved by the caller). */
    @Query("SELECT m FROM WhatsAppMessage m WHERE m.organizationId = :orgId AND m.normalizedPhone IN :phones " +
           "AND m.createdAt = (SELECT MAX(m2.createdAt) FROM WhatsAppMessage m2 " +
           "WHERE m2.organizationId = :orgId AND m2.normalizedPhone = m.normalizedPhone)")
    List<WhatsAppMessage> findLatestPerPhone(@Param("orgId") Long organizationId,
                                             @Param("phones") Collection<String> phones);

    Optional<WhatsAppMessage> findByWaMessageId(String waMessageId);
    Optional<WhatsAppMessage> findFirstByWaMessageIdAndOrganizationId(String waMessageId, Long organizationId);
    boolean existsByWaMessageIdAndOrganizationId(String waMessageId, Long organizationId);
    Optional<WhatsAppMessage> findByIdAndOrganizationId(Long id, Long organizationId);
}
