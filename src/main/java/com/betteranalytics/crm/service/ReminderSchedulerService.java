package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.Reminder;
import com.betteranalytics.crm.repository.ReminderRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fires due reminders once a minute.
 *
 * Due rows are claimed in small batches ({@link #BATCH_SIZE}), each batch in
 * its own short transaction:
 *   - PostgreSQL: {@code SELECT … FOR UPDATE SKIP LOCKED} + one UPDATE, so two
 *     app instances (or an overlapping tick) take disjoint batches without
 *     blocking each other.
 *   - other engines (H2 in dev/tests): a conditional {@code UPDATE … WHERE
 *     is_fired = FALSE} per row — only the caller that flips the flag fires it.
 * The in-app notification row is written in the claiming transaction (so a
 * failure rolls the claim back and the reminder is retried next tick); push
 * delivery is deferred by NotificationService until after that commit, so a
 * slow push never holds row locks. A tick processes at most
 * {@link #MAX_BATCHES_PER_TICK} batches.
 */
@Service
public class ReminderSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(ReminderSchedulerService.class);
    static final int BATCH_SIZE = 50;
    static final int MAX_BATCHES_PER_TICK = 20;

    private final ReminderRepository reminderRepo;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;
    private final DataSource dataSource;
    private volatile Boolean postgres;

    public ReminderSchedulerService(ReminderRepository reminderRepo,
                                     NotificationService notificationService,
                                     PlatformTransactionManager transactionManager,
                                     DataSource dataSource) {
        this.reminderRepo = reminderRepo;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "reminders.fire", lockAtMostFor = "PT5M")
    public void fireReminders() {
        int fired = 0;
        for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
            int n;
            try {
                n = fireBatch(Instant.now());
            } catch (Exception e) {
                log.warn("Reminder batch failed: {}", e.getClass().getSimpleName());
                break;
            }
            fired += n;
            if (n < BATCH_SIZE) break;
        }
        if (fired > 0) log.info("Fired {} reminder(s)", fired);
    }

    /** Claims and fires one batch in one transaction; returns how many reminders were fired. */
    public int fireBatch(Instant now) {
        Integer n = tx.execute(status -> {
            List<Reminder> claimed = isPostgres() ? claimWithSkipLocked(now) : claimConditionally(now);
            for (Reminder r : claimed) {
                notificationService.notify(r.getOrganizationId(), r.getUserId(),
                        "reminder", r.getTitle(), r.getBody(), r.getRelatedType(), r.getRelatedId());
            }
            return claimed.size();
        });
        return n == null ? 0 : n;
    }

    private List<Reminder> claimWithSkipLocked(Instant now) {
        List<Long> ids = reminderRepo.lockDueBatch(now, BATCH_SIZE);
        if (ids.isEmpty()) return List.of();
        reminderRepo.markFired(ids, now);
        return reminderRepo.findAllById(ids);
    }

    private List<Reminder> claimConditionally(Instant now) {
        List<Reminder> claimed = new ArrayList<>();
        for (Reminder r : reminderRepo.findPendingDue(now, BATCH_SIZE)) {
            if (reminderRepo.claim(r.getId(), now) == 1) claimed.add(r);
        }
        return claimed;
    }

    private boolean isPostgres() {
        Boolean p = postgres;
        if (p == null) {
            try (Connection c = dataSource.getConnection()) {
                p = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
            } catch (Exception e) {
                p = false;
            }
            postgres = p;
        }
        return p;
    }
}
