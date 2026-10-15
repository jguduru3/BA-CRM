package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.EmailAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface EmailAccountRepository extends JpaRepository<EmailAccount, Long> {
    List<EmailAccount> findByOrganizationIdAndIsActiveTrue(Long orgId);
    List<EmailAccount> findByUserId(Long userId);
    Optional<EmailAccount> findByIdAndOrganizationId(Long id, Long orgId);
}
