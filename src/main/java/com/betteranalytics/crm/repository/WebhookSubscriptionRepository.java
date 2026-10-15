package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, Long> {
    List<WebhookSubscription> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    List<WebhookSubscription> findByOrganizationIdAndActiveTrue(Long organizationId);

    Optional<WebhookSubscription> findByIdAndOrganizationId(Long id, Long organizationId);
}
