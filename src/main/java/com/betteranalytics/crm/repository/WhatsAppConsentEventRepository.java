package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WhatsAppConsentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WhatsAppConsentEventRepository extends JpaRepository<WhatsAppConsentEvent, Long> {
    List<WhatsAppConsentEvent> findByOrganizationIdAndNormalizedPhoneOrderByCreatedAtDescIdDesc(
            Long organizationId, String normalizedPhone, Pageable pageable);
}
