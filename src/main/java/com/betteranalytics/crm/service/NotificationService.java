package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.AppNotification;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AppNotificationRepository;
import com.betteranalytics.crm.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Creates in-app notifications and sends a push for each one (FCM HTTP v1 via
 * {@link PushService}; disabled cleanly when FCM_SERVICE_ACCOUNT_JSON is unset).
 *
 * Push delivery is strictly best-effort:
 *   - the in-app notification row is the source of truth;
 *   - pushes never run inside the caller's DB transaction: when one is active
 *     they are dispatched after it commits (and dropped if it rolls back),
 *     otherwise immediately — in both cases on a small bounded pool with short
 *     connect/read timeouts, so a slow FCM never blocks a request;
 *   - nothing here throws to the caller because a push failed, and device
 *     tokens are never logged.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final AppNotificationRepository notifRepo;
    private final UserRepository            userRepo;
    private final PushService               pushService;
    private final ThreadPoolExecutor        pushExecutor;

    public NotificationService(AppNotificationRepository notifRepo,
                               UserRepository userRepo,
                               PushService pushService) {
        this.notifRepo = notifRepo;
        this.userRepo = userRepo;
        this.pushService = pushService;
        // Bounded pool + queue; when saturated, pushes are dropped (best-effort) rather than blocking callers.
        this.pushExecutor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(500),
                r -> { Thread t = new Thread(r, "fcm-push"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @PreDestroy
    void shutdown() {
        pushExecutor.shutdown();
    }

    /**
     * Create an in-app notification for a specific user and push it to their devices.
     */
    public AppNotification notify(Long orgId, Long userId, String type, String title, String body,
                                  String relatedType, Long relatedId) {
        AppNotification n = new AppNotification();
        n.setOrganizationId(orgId);
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setBody(body);
        n.setRelatedType(relatedType);
        n.setRelatedId(relatedId);
        n.setRead(false);
        n.setCreatedAt(Instant.now());
        n = notifRepo.save(n);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("notificationId", String.valueOf(n.getId()));
        if (type != null) data.put("type", type);
        if (relatedType != null) data.put("relatedType", relatedType);
        if (relatedId != null) data.put("relatedId", String.valueOf(relatedId));
        schedulePush(userId, title, body, data);
        return n;
    }

    /**
     * Create a notification for all SUPER_ADMIN and VP_SALES users in an org (system events).
     */
    public void createSystemNotification(Long orgId, String type, String title, String body,
                                         String relatedType) {
        for (User u : admins(orgId)) {
            notify(orgId, u.getId(), type, title, body, relatedType, null);
        }
    }

    /**
     * Notify all SUPER_ADMIN and VP_SALES users in an org, with a related record link.
     * Used for approval requests, system alerts, etc.
     */
    public void notifyAdmins(Long orgId, String type, String title, String body,
                             String relatedType, Long relatedId) {
        for (User u : admins(orgId)) {
            notify(orgId, u.getId(), type, title, body, relatedType, relatedId);
        }
    }

    private List<User> admins(Long orgId) {
        return userRepo.findByOrganizationId(orgId).stream()
                .filter(u -> u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.VP_SALES)
                .filter(User::isActive)
                .toList();
    }

    /** Lead-assigned notification to the new owner (LeadController). */
    public void notifyLeadAssigned(Long orgId, Long toUserId, String leadName, Long leadId) {
        notify(orgId, toUserId, "lead_assigned",
                "Lead assigned: " + leadName,
                "You have been assigned a new lead.", "Lead", leadId);
    }

    /** Task-assigned notification to the assignee (TaskController). */
    public void notifyTaskAssigned(Long orgId, Long toUserId, String taskTitle, Long taskId) {
        notify(orgId, toUserId, "task_due",
                "New task: " + taskTitle,
                "A task has been assigned to you.", "Task", taskId);
    }

    /** Opportunity-stage-changed notification to the owner (OpportunityController). */
    public void notifyOppStageChanged(Long orgId, Long toUserId, String oppName,
                                      String newStage, Long oppId) {
        notify(orgId, toUserId, "opp_stage_changed",
                "Opportunity updated: " + oppName,
                "Stage changed to " + newStage, "Opportunity", oppId);
    }

    // ── Push dispatch ────────────────────────────────────────────────────────

    /** Defers the push until after the surrounding transaction commits (or runs it now if there is none). */
    private void schedulePush(Long userId, String title, String body, Map<String, String> data) {
        if (!pushService.isEnabled() || userId == null) return;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch(userId, title, body, data);
                    }
                });
            } else {
                dispatch(userId, title, body, data);
            }
        } catch (Exception e) {
            log.warn("Could not schedule push for userId={}: {}", userId, e.getClass().getSimpleName());
        }
    }

    private void dispatch(Long userId, String title, String body, Map<String, String> data) {
        try {
            pushExecutor.execute(() -> pushService.sendToUser(userId, title, body, data));
        } catch (Exception e) {
            log.warn("Could not queue push for userId={}: {}", userId, e.getClass().getSimpleName());
        }
    }
}
