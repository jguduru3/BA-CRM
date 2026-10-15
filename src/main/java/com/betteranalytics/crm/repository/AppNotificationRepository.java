package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.AppNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AppNotificationRepository extends JpaRepository<AppNotification, Long> {
    // read=false means unread — those sort first (false < true), then newest first
    List<AppNotification> findByUserIdAndOrganizationIdOrderByReadAscCreatedAtDesc(Long userId, Long organizationId);
    List<AppNotification> findByUserIdAndOrganizationIdOrderByReadAscCreatedAtDesc(Long userId, Long organizationId, Pageable pageable);
    Optional<AppNotification> findByIdAndUserId(Long id, Long userId);
    Optional<AppNotification> findByIdAndUserIdAndOrganizationId(Long id, Long userId, Long organizationId);
    @Modifying
    @Query("UPDATE AppNotification n SET n.read = true WHERE n.userId = :userId AND n.organizationId = :orgId AND n.read = false")
    int markAllReadByUserId(@Param("userId") Long userId, @Param("orgId") Long orgId);
    long countByUserIdAndOrganizationIdAndReadFalse(Long userId, Long organizationId);
}
