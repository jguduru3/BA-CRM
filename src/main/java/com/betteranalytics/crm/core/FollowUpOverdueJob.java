package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.service.NotificationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Every 15 minutes: tell each lead owner, once, that a follow-up date has passed.
 *
 * "Once" is tracked in leads.follow_up_notified_at, set with a conditional UPDATE
 * (… WHERE follow_up_notified_at IS NULL AND follow_up_at = :date) in the same
 * short transaction as the in-app notification — so overlapping ticks or two
 * replicas never double-notify. Changing the follow-up date clears the flag
 * (LeadController), which re-arms the reminder for the new date.
 */
@Service
public class FollowUpOverdueJob {

    private static final Logger log = LoggerFactory.getLogger(FollowUpOverdueJob.class);
    static final int BATCH_SIZE = 500;

    private final LeadRepository leadRepository;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;

    public FollowUpOverdueJob(LeadRepository leadRepository, NotificationService notificationService,
                              PlatformTransactionManager transactionManager) {
        this.leadRepository = leadRepository;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.followups.overdue-check-ms:900000}",
               initialDelayString = "${app.followups.initial-delay-ms:120000}")
    @SchedulerLock(name = "leads.followUpOverdue", lockAtMostFor = "PT10M")
    public void scheduled() {
        notifyOverdue(Instant.now());
    }

    /** @return how many owners were notified */
    public int notifyOverdue(Instant now) {
        List<Lead> due = leadRepository.findOverdueFollowUps(now, PageRequest.of(0, BATCH_SIZE));
        int sent = 0;
        for (Lead lead : due) {
            try {
                Boolean ok = tx.execute(status -> {
                    if (leadRepository.markFollowUpNotified(lead.getId(), lead.getFollowUpAt(), now) != 1) return false;
                    notificationService.notify(lead.getOrganizationId(), lead.getOwnerId(), "follow_up_overdue",
                            "Follow-up overdue: " + lead.getName(),
                            "The follow-up planned for this lead has passed. Reach out or reschedule.",
                            "Lead", lead.getId());
                    return true;
                });
                if (Boolean.TRUE.equals(ok)) sent++;
            } catch (Exception e) {
                log.warn("Overdue follow-up notification failed for lead id={}: {}", lead.getId(), e.getClass().getSimpleName());
            }
        }
        if (sent > 0) log.info("Sent {} overdue follow-up notification(s)", sent);
        return sent;
    }
}
