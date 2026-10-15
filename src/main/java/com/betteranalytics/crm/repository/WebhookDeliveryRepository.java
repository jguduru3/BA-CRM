package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.WebhookDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {
    List<WebhookDelivery> findBySubscriptionIdAndOrganizationIdOrderByCreatedAtDesc(
            Long subscriptionId, Long organizationId, Pageable pageable);
}
