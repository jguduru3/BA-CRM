package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.EmailConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmailConsentRepository extends JpaRepository<EmailConsent, Long> {
    Optional<EmailConsent> findByOrganizationIdAndEmailNormalized(Long organizationId, String emailNormalized);

    /** Which of these addresses are opted out — one indexed query per batch. */
    @Query("SELECT c.emailNormalized FROM EmailConsent c WHERE c.organizationId = :orgId " +
           "AND c.status = 'opted_out' AND c.emailNormalized IN :emails")
    List<String> findOptedOut(@Param("orgId") Long organizationId, @Param("emails") Collection<String> emails);
}
