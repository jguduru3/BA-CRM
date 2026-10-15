package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<ApiKey> findByIdAndOrganizationId(Long id, Long organizationId);

    /** One UPDATE per request instead of load-modify-save. */
    @Modifying
    @Transactional
    @Query("UPDATE ApiKey k SET k.requestCount = k.requestCount + 1, k.lastUsedAt = :now, k.lastUsedIp = :ip WHERE k.id = :id")
    int recordUse(@Param("id") Long id, @Param("now") Instant now, @Param("ip") String ip);
}
