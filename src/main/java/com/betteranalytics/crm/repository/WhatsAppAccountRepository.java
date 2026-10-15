package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WhatsAppAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WhatsAppAccountRepository extends JpaRepository<WhatsAppAccount, Long> {
    List<WhatsAppAccount> findByOrganizationId(Long organizationId);
    Optional<WhatsAppAccount> findByIdAndOrganizationId(Long id, Long organizationId);
    Optional<WhatsAppAccount> findFirstByOrganizationIdAndActiveTrue(Long organizationId);
    // phone_number_id is globally unique (V15)
    Optional<WhatsAppAccount> findByPhoneNumberId(String phoneNumberId);
    List<WhatsAppAccount> findByWebhookVerifyToken(String webhookVerifyToken);
}
