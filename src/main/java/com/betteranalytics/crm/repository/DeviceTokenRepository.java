package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    List<DeviceToken> findByUserId(Long userId);
    Optional<DeviceToken> findByUserIdAndToken(Long userId, String token);

    @Modifying
    @Query("DELETE FROM DeviceToken dt WHERE dt.userId = :userId AND dt.token = :token")
    void deleteByUserIdAndToken(@Param("userId") Long userId, @Param("token") String token);

    /** A token identifies one physical device: drop any binding of it to a different user. */
    @Modifying
    @Query("DELETE FROM DeviceToken dt WHERE dt.token = :token AND dt.userId <> :userId")
    int deleteByTokenForOtherUsers(@Param("token") String token, @Param("userId") Long userId);

    boolean existsByUserIdAndActiveTrue(Long userId);
}
