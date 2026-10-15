package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByOrganizationIdAndUserId(Long orgId, Long userId);
    Optional<Reminder> findByIdAndOrganizationId(Long id, Long orgId);
    Optional<Reminder> findByIdAndOrganizationIdAndUserId(Long id, Long orgId, Long userId);

    @Query(value = "SELECT * FROM reminders WHERE is_fired = FALSE AND remind_at <= :now", nativeQuery = true)
    List<Reminder> findPendingDue(@Param("now") Instant now);

    /** Bounded batch of due reminders, oldest first — the scheduler processes at most :limit per tick. */
    @Query(value = "SELECT * FROM reminders WHERE is_fired = FALSE AND remind_at <= :now ORDER BY remind_at ASC, id ASC LIMIT :limit",
           nativeQuery = true)
    List<Reminder> findPendingDue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Atomically claims a reminder for firing. Returns 1 only for the single
     * caller (thread or app instance) that flipped is_fired; everyone else gets 0.
     */
    @Modifying
    @Query(value = "UPDATE reminders SET is_fired = TRUE, fired_at = :now WHERE id = :id AND is_fired = FALSE",
           nativeQuery = true)
    int claim(@Param("id") Long id, @Param("now") Instant now);
}
