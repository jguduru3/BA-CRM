package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.WebhookDelivery;
import com.betteranalytics.crm.entity.WebhookSubscription;
import com.betteranalytics.crm.repository.WebhookDeliveryRepository;
import com.betteranalytics.crm.repository.WebhookSubscriptionRepository;
import com.betteranalytics.crm.security.UrlSafety;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Outbound webhooks: every CRM change recorded by AuditService is turned into
 * an event ("lead.created", "opportunity.updated", ...) and POSTed to each
 * matching active subscription of the same organization.
 *
 * Delivery contract (documented in docs/MASTER_API.md):
 *   headers  X-BetterCRM-Event, X-BetterCRM-Delivery (unique id, use for
 *            idempotency), X-BetterCRM-Timestamp (unix seconds),
 *            X-BetterCRM-Signature: sha256=hex(HMAC_SHA256(secret, timestamp + "." + body))
 *   body     {"id","event","createdAt","organizationId","data":{...}}
 *   success  any 2xx within 10 s; otherwise retried after 10 s, 1 min, 5 min.
 *   A subscription is auto-disabled after 20 consecutive failed deliveries.
 *
 * Events are dispatched only after the surrounding transaction commits, so a
 * rolled-back change never produces an event. Every attempt is recorded in
 * webhook_deliveries. Targets are SSRF-checked on every send and redirects are
 * never followed.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final long[] RETRY_DELAYS_SECONDS = {10, 60, 300};
    private static final int AUTO_DISABLE_AFTER = 20;

    /** The event catalogue advertised by the Master API. */
    public static final List<String> EVENTS = List.of(
            "lead.created", "lead.updated", "lead.deleted",
            "opportunity.created", "opportunity.updated", "opportunity.deleted",
            "company.created", "company.updated", "company.deleted",
            "contact.created", "contact.updated", "contact.deleted",
            "task.created", "task.updated", "task.deleted",
            "activity.created",
            "note.created", "note.updated", "note.deleted",
            "meeting.created", "meeting.updated", "meeting.deleted",
            "project.created", "project.updated", "project.deleted",
            "campaign.created", "campaign.updated", "campaign.deleted",
            "document.created", "document.updated", "document.deleted",
            "user.created", "user.updated",
            "webhook.test");

    private static final Map<String, String> RESOURCE_TO_EVENT_NOUN = Map.ofEntries(
            Map.entry("leads", "lead"), Map.entry("opportunities", "opportunity"),
            Map.entry("companies", "company"), Map.entry("contacts", "contact"),
            Map.entry("tasks", "task"), Map.entry("activities", "activity"),
            Map.entry("notes", "note"), Map.entry("meetings", "meeting"),
            Map.entry("projects", "project"), Map.entry("campaigns", "campaign"),
            Map.entry("documents", "document"), Map.entry("users", "user"));

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "webhook-delivery");
        t.setDaemon(true);
        return t;
    });

    public WebhookService(WebhookSubscriptionRepository subscriptionRepository,
                          WebhookDeliveryRepository deliveryRepository,
                          ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    /** Maps an audit (action, resource) pair to an event name, or null when it isn't published. */
    public static String eventFor(String action, String resource) {
        String noun = RESOURCE_TO_EVENT_NOUN.get(resource);
        if (noun == null || action == null) return null;
        String verb = switch (action) {
            case "create" -> "created";
            case "update" -> "updated";
            case "delete" -> "deleted";
            default -> null;
        };
        if (verb == null) return null;
        String event = noun + "." + verb;
        return EVENTS.contains(event) ? event : null;
    }

    /** Queue an event for every matching subscription; runs after commit when inside a transaction. */
    public void publish(Long organizationId, String event, Object data) {
        if (organizationId == null || event == null) return;
        Runnable dispatch = () -> {
            try {
                List<WebhookSubscription> subs = subscriptionRepository.findByOrganizationIdAndActiveTrue(organizationId);
                if (subs.isEmpty()) return;
                String body = envelope(organizationId, event, data);
                for (WebhookSubscription s : subs) {
                    if (s.matches(event)) {
                        executor.execute(() -> attempt(s.getId(), event, body, UUID.randomUUID().toString(), 1, true));
                    }
                }
            } catch (Exception e) {
                log.warn("webhook publish failed for org={} event={}: {}", organizationId, event, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    /** Synchronous single delivery used by the "Send test event" button. */
    public WebhookDelivery sendTest(WebhookSubscription s) {
        String body = envelope(s.getOrganizationId(), "webhook.test",
                Map.of("message", "Test event from Better CRM", "subscriptionId", s.getId()));
        return attempt(s.getId(), "webhook.test", body, UUID.randomUUID().toString(), 1, false);
    }

    public String envelope(Long organizationId, String event, Object data) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("id", "evt_" + UUID.randomUUID().toString().replace("-", ""));
        env.put("event", event);
        env.put("createdAt", Instant.now().toString());
        env.put("organizationId", organizationId);
        env.put("data", data);
        try {
            return objectMapper.writeValueAsString(env);
        } catch (Exception e) {
            env.put("data", Map.of("serializationError", true));
            try {
                return objectMapper.writeValueAsString(env);
            } catch (Exception ex) {
                return "{\"event\":\"" + event + "\"}";
            }
        }
    }

    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(sig);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WebhookDelivery attempt(Long subscriptionId, String event, String body, String deliveryUid, int attempt,
                                    boolean retryOnFailure) {
        WebhookSubscription s = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (s == null || (!s.isActive() && !"webhook.test".equals(event))) return null;

        WebhookDelivery d = new WebhookDelivery();
        d.setSubscriptionId(s.getId());
        d.setOrganizationId(s.getOrganizationId());
        d.setEvent(event);
        d.setDeliveryUid(deliveryUid);
        d.setAttempt(attempt);
        d.setRequestBody(body.length() > 20_000 ? body.substring(0, 20_000) : body);
        d.setCreatedAt(Instant.now());

        long started = System.nanoTime();
        try {
            URI uri = UrlSafety.requireSafe(s.getUrl());
            long ts = Instant.now().getEpochSecond();
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "BetterCRM-Webhooks/1.0")
                    .header("X-BetterCRM-Event", event)
                    .header("X-BetterCRM-Delivery", deliveryUid)
                    .header("X-BetterCRM-Timestamp", String.valueOf(ts))
                    .header("X-BetterCRM-Signature", sign(s.getSecret(), ts, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            d.setStatusCode(res.statusCode());
            d.setSuccess(res.statusCode() >= 200 && res.statusCode() < 300);
            if (!d.isSuccess()) d.setError("HTTP " + res.statusCode());
        } catch (IllegalArgumentException e) {
            d.setError("Blocked: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            d.setError("Interrupted");
        } catch (Exception e) {
            d.setError(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + truncate(e.getMessage(), 300)));
        }
        d.setDurationMs((System.nanoTime() - started) / 1_000_000);
        record(s, d);

        if (retryOnFailure && !d.isSuccess() && attempt <= RETRY_DELAYS_SECONDS.length) {
            long delay = RETRY_DELAYS_SECONDS[attempt - 1];
            executor.schedule(() -> attempt(subscriptionId, event, body, deliveryUid, attempt + 1, true), delay, TimeUnit.SECONDS);
        }
        return d;
    }

    private void record(WebhookSubscription s, WebhookDelivery d) {
        try {
            deliveryRepository.save(d);
            WebhookSubscription fresh = subscriptionRepository.findById(s.getId()).orElse(null);
            if (fresh == null) return;
            fresh.setLastDeliveryAt(d.getCreatedAt());
            fresh.setLastStatusCode(d.getStatusCode());
            if (d.isSuccess()) {
                fresh.setConsecutiveFailures(0);
            } else if (!"webhook.test".equals(d.getEvent())) {
                fresh.setConsecutiveFailures(fresh.getConsecutiveFailures() + 1);
                if (fresh.getConsecutiveFailures() >= AUTO_DISABLE_AFTER) {
                    fresh.setActive(false);
                    log.warn("Webhook {} disabled after {} consecutive failures", fresh.getId(), AUTO_DISABLE_AFTER);
                }
            }
            subscriptionRepository.save(fresh);
        } catch (Exception e) {
            log.warn("could not record webhook delivery for subscription {}: {}", s.getId(), e.getMessage());
        }
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
