package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WhatsAppConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WhatsAppConsentRepository extends JpaRepository<WhatsAppConsent, Long> {
    Optional<WhatsAppConsent> findByOrganizationIdAndNormalizedPhone(Long organizationId, String normalizedPhone);

    /** Which of these numbers are opted out — one indexed query for a whole page/batch. */
    @Query("SELECT c.normalizedPhone FROM WhatsAppConsent c WHERE c.organizationId = :orgId " +
           "AND c.status = 'opted_out' AND c.normalizedPhone IN :phones")
    List<String> findOptedOut(@Param("orgId") Long organizationId, @Param("phones") Collection<String> phones);
}
