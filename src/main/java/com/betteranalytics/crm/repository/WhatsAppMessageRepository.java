package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WhatsAppMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, Long> {

    // Conversation thread for a linked CRM record
    List<WhatsAppMessage> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
            Long organizationId, String relatedType, Long relatedId);

    List<WhatsAppMessage> findByOrganizationIdAndRelatedTypeAndRelatedIdOrderByCreatedAtDesc(
            Long organizationId, String relatedType, Long relatedId, Pageable pageable);

    // Conversation thread by phone: matches both inbound (fromNumber) and outbound (toNumber)
    @Query("SELECT m FROM WhatsAppMessage m WHERE m.organizationId = :orgId " +
           "AND (m.fromNumber = :phone OR m.toNumber = :phone) ORDER BY m.createdAt DESC")
    List<WhatsAppMessage> findByOrganizationIdAndPhoneOrderByCreatedAtDesc(
            @Param("orgId") Long organizationId, @Param("phone") String phone, Pageable pageable);

    Optional<WhatsAppMessage> findByWaMessageId(String waMessageId);
    Optional<WhatsAppMessage> findFirstByWaMessageIdAndOrganizationId(String waMessageId, Long organizationId);
    boolean existsByWaMessageIdAndOrganizationId(String waMessageId, Long organizationId);
    Optional<WhatsAppMessage> findByIdAndOrganizationId(Long id, Long organizationId);
}
