package com.betteranalytics.crm.repository;

import com.betteranalytics.crm.entity.Reminder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByOrganizationIdAndUserId(Long orgId, Long userId);
    Optional<Reminder> findByIdAndOrganizationId(Long id, Long orgId);
    Optional<Reminder> findByIdAndOrganizationIdAndUserId(Long id, Long orgId, Long userId);

    /** The caller's reminders, optionally only pending/fired ones, sorted and paged in the DB. */
    @Query("SELECT r FROM Reminder r WHERE r.organizationId = :orgId AND r.userId = :userId " +
           "AND (:fired IS NULL OR r.isFired = :fired) ORDER BY r.remindAt ASC, r.id ASC")
    List<Reminder> findForUser(@Param("orgId") Long orgId, @Param("userId") Long userId,
                               @Param("fired") Boolean fired, Pageable page);

    @Query(value = "SELECT * FROM reminders WHERE is_fired = FALSE AND remind_at <= :now", nativeQuery = true)
    List<Reminder> findPendingDue(@Param("now") Instant now);

    /** Bounded batch of due reminders, oldest first — the scheduler processes at most :limit per tick. */
    @Query(value = "SELECT * FROM reminders WHERE is_fired = FALSE AND remind_at <= :now ORDER BY remind_at ASC, id ASC LIMIT :limit",
           nativeQuery = true)
    List<Reminder> findPendingDue(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * PostgreSQL batch claim: locks up to :limit due rows and skips rows another
     * instance has already locked, so concurrent workers never wait on — or
     * double-process — each other's batch. Must run inside a transaction.
     */
    @Query(value = "SELECT id FROM reminders WHERE is_fired = FALSE AND remind_at <= :now " +
                   "ORDER BY remind_at ASC, id ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Long> lockDueBatch(@Param("now") Instant now, @Param("limit") int limit);

    /** Marks a locked batch fired (PostgreSQL path, same transaction as lockDueBatch). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE reminders SET is_fired = TRUE, fired_at = :now WHERE id IN (:ids) AND is_fired = FALSE",
           nativeQuery = true)
    int markFired(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /**
     * Atomically claims a reminder for firing. Returns 1 only for the single
     * caller (thread or app instance) that flipped is_fired; everyone else gets 0.
     */
    @Modifying
    @Query(value = "UPDATE reminders SET is_fired = TRUE, fired_at = :now WHERE id = :id AND is_fired = FALSE",
           nativeQuery = true)
    int claim(@Param("id") Long id, @Param("now") Instant now);
}
