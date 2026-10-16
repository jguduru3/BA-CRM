package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Reminder;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AppNotificationRepository;
import com.betteranalytics.crm.repository.ReminderRepository;
import com.betteranalytics.crm.service.ReminderSchedulerService;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReminderTest extends IntegrationTest {

    @Autowired ReminderRepository reminderRepository;
    @Autowired AppNotificationRepository notificationRepository;
    @Autowired ReminderSchedulerService scheduler;

    @Test
    void remindersArePersonalAndSupportCannotWrite() throws Exception {
        Long org = newOrg();
        User a = user(org, Role.EXECUTIVE);
        User b = user(org, Role.SUPER_ADMIN);
        User support = user(org, Role.SUPPORT);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long id = idOf(postAs(a, "/api/reminders", Map.of("title", "Call back", "remindAt", "2030-01-01T09:00:00Z"))
                .andExpect(status().isCreated()));
        deleteAs(b, "/api/reminders/" + id).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/reminders/" + id).andExpect(status().isNotFound());
        assertThat(body(getAs(b, "/api/reminders")).size()).isZero();
        getAs(support, "/api/reminders").andExpect(status().isOk());
        postAs(support, "/api/reminders", Map.of("title", "x", "remindAt", "2030-01-01T09:00:00Z")).andExpect(status().isForbidden());
        postAs(a, "/api/reminders", Map.of("title", "x", "remindAt", "2030-01-01T09:00:00Z", "relatedType", "Lead", "relatedId", 999999))
                .andExpect(status().isBadRequest());
        deleteAs(a, "/api/reminders/" + id).andExpect(status().isOk());
    }

    private Reminder due(User u, String title) {
        Reminder r = new Reminder();
        r.setOrganizationId(u.getOrganizationId());
        r.setUserId(u.getId());
        r.setTitle(title);
        r.setRemindAt(Instant.now().minusSeconds(60));
        r.setCreatedAt(Instant.now());
        return reminderRepository.save(r);
    }

    @Test
    void schedulerFiresEachDueReminderExactlyOnceEvenWhenRunConcurrently() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 120; i++) ids.add(due(u, "r" + i).getId());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger total = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(() -> {
                // A batch that loses a row-lock race may fail and roll back as a whole —
                // exactly what the scheduler does too (it simply retries next tick).
                for (int attempt = 0, idle = 0; attempt < 200 && idle < 3; attempt++) {
                    try {
                        int n = scheduler.fireBatch(Instant.now());
                        total.addAndGet(n);
                        idle = n == 0 ? idle + 1 : 0;
                    } catch (RuntimeException raced) {
                        idle = 0;
                    }
                }
            }));
        }
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        // Other tests' reminders may be due too, so only count ours.
        long ours = reminderRepository.findAllById(ids).stream().filter(Reminder::isFired).count();
        assertThat(ours).isEqualTo(120);
        assertThat(notificationRepository.findByUserIdAndOrganizationIdOrderByReadAscCreatedAtDesc(u.getId(), u.getOrganizationId()))
                .hasSize(120);
        assertThat(scheduler.fireBatch(Instant.now())).isGreaterThanOrEqualTo(0);
        assertThat(notificationRepository.findByUserIdAndOrganizationIdOrderByReadAscCreatedAtDesc(u.getId(), u.getOrganizationId()))
                .hasSize(120);
    }
}
