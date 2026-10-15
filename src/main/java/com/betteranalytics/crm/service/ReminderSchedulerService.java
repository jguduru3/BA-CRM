package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.Reminder;
import com.betteranalytics.crm.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Fires due reminders once a minute.
 *
 * Each reminder is handled in its own short transaction: it is first claimed
 * with an atomic {@code UPDATE ... WHERE is_fired = FALSE} (so two app
 * instances, or an overlapping tick, can never fire the same reminder twice),
 * then its in-app notification is written in the same transaction. Push
 * delivery is deferred by NotificationService until after that transaction
 * commits, so a slow/failed push never holds DB locks or rolls back the claim.
 * At most {@link #BATCH_SIZE} reminders are processed per tick.
 */
@Service
public class ReminderSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(ReminderSchedulerService.class);
    static final int BATCH_SIZE = 200;

    private final ReminderRepository reminderRepo;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;

    public ReminderSchedulerService(ReminderRepository reminderRepo,
                                     NotificationService notificationService,
                                     PlatformTransactionManager transactionManager) {
        this.reminderRepo = reminderRepo;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 60_000)
    public void fireReminders() {
        List<Reminder> due = reminderRepo.findPendingDue(Instant.now(), BATCH_SIZE);
        if (due.isEmpty()) return;
        int fired = 0;
        for (Reminder r : due) {
            try {
                Boolean ok = tx.execute(status -> {
                    if (reminderRepo.claim(r.getId(), Instant.now()) != 1) return false; // already fired elsewhere
                    notificationService.notify(r.getOrganizationId(), r.getUserId(),
                            "reminder", r.getTitle(), r.getBody(),
                            r.getRelatedType(), r.getRelatedId());
                    return true;
                });
                if (Boolean.TRUE.equals(ok)) fired++;
            } catch (Exception e) {
                log.warn("Failed to fire reminder id={}: {}", r.getId(), e.getClass().getSimpleName());
            }
        }
        if (fired > 0) log.info("Fired {} reminder(s)", fired);
    }
}
