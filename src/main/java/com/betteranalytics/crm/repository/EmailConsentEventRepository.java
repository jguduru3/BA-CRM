package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.EmailConsentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmailConsentEventRepository extends JpaRepository<EmailConsentEvent, Long> {
    List<EmailConsentEvent> findByOrganizationIdAndEmailNormalizedOrderByCreatedAtDescIdDesc(
            Long organizationId, String emailNormalized, Pageable pageable);
}
