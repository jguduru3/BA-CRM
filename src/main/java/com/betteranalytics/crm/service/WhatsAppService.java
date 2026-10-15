package com.betteranalytics.crm.service;

import com.betteranalytics.crm.config.IntegrationAsyncConfig;
import com.betteranalytics.crm.config.IntegrationAsyncConfig.IntegrationExecutor;
import com.betteranalytics.crm.entity.Campaign;
import com.betteranalytics.crm.entity.CampaignContact;
import com.betteranalytics.crm.entity.WhatsAppAccount;
import com.betteranalytics.crm.entity.WhatsAppMessage;
import com.betteranalytics.crm.repository.CampaignContactRepository;
import com.betteranalytics.crm.repository.CampaignRepository;
import com.betteranalytics.crm.repository.WhatsAppAccountRepository;
import com.betteranalytics.crm.repository.WhatsAppMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Thin wrapper around Meta's WhatsApp Cloud API (Graph API v19.0), plus
 * webhook processing and background campaign dispatch.
 *
 * To wire up: set WHATSAPP_GRAPH_URL (default https://graph.facebook.com/v19.0),
 * WHATSAPP_APP_SECRET (used to verify X-Hub-Signature-256 on webhooks) and
 * configure a WhatsAppAccount row with the org's credentials.
 */
@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

    /** E.164-ish: optional '+', 8–15 digits, no leading zero. */
    private static final Pattern PHONE = Pattern.compile("^\\+?[1-9]\\d{7,14}$");
    private static final Pattern PHONE_FORMATTING = Pattern.compile("[\\s\\-().]");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}(_[A-Z]{2})?$");
    private static final Pattern TEMPLATE_NAME = Pattern.compile("^[A-Za-z0-9_]{1,100}$");
    private static final Pattern PHONE_NUMBER_ID = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    private static final Set<String> STATUS_EVENTS = Set.of("sent", "delivered", "read", "failed");
    private static final int DISPATCH_BATCH = 100;
    public static final String DEFAULT_LANGUAGE = "en_US";

    private final WhatsAppAccountRepository  accountRepo;
    private final WhatsAppMessageRepository  messageRepo;
    private final CampaignRepository         campaignRepo;
    private final CampaignContactRepository  contactRepo;
    private final RestTemplate               restTemplate;
    private final ObjectMapper               objectMapper;
    private final NotificationService        notificationService;
    private final IntegrationExecutor        executor;

    /** Campaign ids with a dispatch worker running in this JVM (per-contact claim guards across JVMs). */
    private final Set<Long> dispatching = Collections.synchronizedSet(new HashSet<>());

    @Value("${app.whatsapp.graph-url:https://graph.facebook.com/v19.0}")
    private String graphUrl;

    @Value("${app.whatsapp.app-secret:}")
    private String appSecret;

    @Value("${app.whatsapp.allow-unsigned-webhooks:false}")
    private boolean allowUnsignedWebhooks;

    public WhatsAppService(WhatsAppAccountRepository accountRepo,
                           WhatsAppMessageRepository messageRepo,
                           CampaignRepository campaignRepo,
                           CampaignContactRepository contactRepo,
                           NotificationService notificationService,
                           ObjectMapper objectMapper,
                           IntegrationExecutor executor) {
        this.accountRepo = accountRepo;
        this.messageRepo = messageRepo;
        this.campaignRepo = campaignRepo;
        this.contactRepo = contactRepo;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.restTemplate = IntegrationAsyncConfig.outboundRestTemplate(5_000, 30_000);
    }

    // ── Validation helpers (shared with controllers) ─────────────────────────

    /** Strips common formatting and validates E.164; throws IllegalArgumentException (→ 400). */
    public static String normalizePhone(String raw) {
        if (raw == null) throw new IllegalArgumentException("phone is required");
        String p = PHONE_FORMATTING.matcher(raw.trim()).replaceAll("");
        if (!PHONE.matcher(p).matches())
            throw new IllegalArgumentException("phone must be an E.164 number, e.g. +919876543210");
        return p;
    }

    public static boolean isValidPhone(String raw) {
        if (raw == null) return false;
        return PHONE.matcher(PHONE_FORMATTING.matcher(raw.trim()).replaceAll("")).matches();
    }

    public static String validateLanguage(String language) {
        if (language == null || language.isBlank()) return DEFAULT_LANGUAGE;
        if (!LANGUAGE.matcher(language).matches())
            throw new IllegalArgumentException("templateLanguage must look like en_US");
        return language;
    }

    public static String validateTemplateName(String name) {
        if (name == null || !TEMPLATE_NAME.matcher(name).matches())
            throw new IllegalArgumentException("templateName must be letters, digits or underscores (max 100)");
        return name;
    }

    public static String validatePhoneNumberId(String id) {
        if (id == null || !PHONE_NUMBER_ID.matcher(id).matches())
            throw new IllegalArgumentException("phoneNumberId must be Meta's numeric Phone Number ID");
        return id;
    }

    /** Parses a template components JSON array; null/blank → null. Bad JSON → IllegalArgumentException. */
    public List<Map<String, Object>> parseTemplateComponents(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {});
            return list == null || list.isEmpty() ? null : list;
        } catch (Exception e) {
            throw new IllegalArgumentException("templateParams must be a JSON array of template component objects");
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    /**
     * Send a free-form text message to a phone number (E.164).
     */
    public WhatsAppMessage sendText(Long orgId, Long accountId, String toPhone, String text,
                                     String relatedType, Long relatedId, Long sentBy) {
        WhatsAppAccount account = requireActiveAccount(orgId, accountId);
        String phone = normalizePhone(toPhone);

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", phone,
                "type", "text",
                "text", Map.of("preview_url", false, "body", text)
        );

        return doSend(account, phone, "text", text, null, null, payload, relatedType, relatedId, sentBy, null);
    }

    /**
     * Send an approved template message. components is the JSON array of parameter objects.
     */
    public WhatsAppMessage sendTemplate(Long orgId, Long accountId, String toPhone,
                                         String templateName, String language,
                                         List<Map<String, Object>> components,
                                         String relatedType, Long relatedId, Long sentBy) {
        WhatsAppAccount account = requireActiveAccount(orgId, accountId);
        return sendTemplate(account, normalizePhone(toPhone), validateTemplateName(templateName),
                validateLanguage(language), components, relatedType, relatedId, sentBy, null);
    }

    private WhatsAppMessage sendTemplate(WhatsAppAccount account, String phone, String templateName,
                                         String language, List<Map<String, Object>> components,
                                         String relatedType, Long relatedId, Long sentBy, Long campaignId) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", language != null ? language : DEFAULT_LANGUAGE));
        if (components != null && !components.isEmpty()) template.put("components", components);

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", phone,
                "type", "template",
                "template", template
        );

        String paramsJson = null;
        if (components != null) {
            try { paramsJson = objectMapper.writeValueAsString(components); } catch (Exception ignored) {}
        }

        return doSend(account, phone, "template", null, templateName, paramsJson, payload,
                relatedType, relatedId, sentBy, campaignId);
    }

    private WhatsAppAccount requireActiveAccount(Long orgId, Long accountId) {
        WhatsAppAccount account = accountRepo.findByIdAndOrganizationId(accountId, orgId)
                .orElseThrow(() -> new NoSuchElementException("WhatsApp account not found"));
        if (!account.isActive()) throw new IllegalStateException("WhatsApp account is inactive");
        return account;
    }

    private WhatsAppMessage doSend(WhatsAppAccount account, String toPhone, String type, String body,
                                    String templateName, String templateParams,
                                    Map<String, Object> payload, String relatedType, Long relatedId,
                                    Long sentBy, Long campaignId) {
        WhatsAppMessage msg = new WhatsAppMessage();
        msg.setOrganizationId(account.getOrganizationId());
        msg.setAccountId(account.getId());
        msg.setDirection("outbound");
        msg.setType(type);
        msg.setToNumber(toPhone);
        msg.setBody(body);
        msg.setTemplateName(templateName);
        msg.setTemplateParams(templateParams);
        msg.setRelatedType(relatedType);
        msg.setRelatedId(relatedId);
        msg.setSentBy(sentBy);
        msg.setCampaignId(campaignId);
        msg.setStatus("sent");
        msg.setCreatedAt(Instant.now());
        msg.setUpdatedAt(Instant.now());

        String token = account.getAccessToken();
        if (token == null || token.isBlank()) {
            msg.setStatus("failed");
            msg.setErrorMessage("WhatsApp account has no usable access token");
            return messageRepo.save(msg);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            String url = graphUrl + "/" + validatePhoneNumberId(account.getPhoneNumberId()) + "/messages";
            ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode messages = root.path("messages");
            if (messages.isArray() && messages.size() > 0) {
                msg.setWaMessageId(messages.get(0).path("id").asText(null));
            }
        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            String detail = graphErrorMessage(e.getResponseBodyAsString());
            log.warn("WhatsApp send failed (HTTP {}) for account={}: {}", code, account.getId(), detail);
            msg.setStatus("failed");
            msg.setErrorCode(String.valueOf(code));
            msg.setErrorMessage(truncate("WhatsApp API error " + code + (detail != null ? ": " + detail : ""), 500));
        } catch (ResourceAccessException e) {
            log.warn("WhatsApp API unreachable for account={}: {}", account.getId(), e.getMessage());
            msg.setStatus("failed");
            msg.setErrorMessage("WhatsApp API unreachable or timed out");
        } catch (Exception e) {
            log.error("WhatsApp send failed for account={}: {}", account.getId(), e.toString());
            msg.setStatus("failed");
            msg.setErrorMessage("WhatsApp send failed");
        }

        return messageRepo.save(msg);
    }

    /** Extracts Meta's human-readable error.message (not the raw body). */
    private String graphErrorMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            String m = objectMapper.readTree(body).path("error").path("message").asText(null);
            return m == null ? null : truncate(m, 300);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Webhook ──────────────────────────────────────────────────────────────

    /**
     * Verifies Meta's X-Hub-Signature-256 header (HMAC-SHA256 of the raw body
     * keyed with the app secret), constant-time. Without a configured secret the
     * webhook is rejected unless app.whatsapp.allow-unsigned-webhooks=true (dev only).
     */
    public boolean isWebhookSignatureValid(byte[] rawBody, String signatureHeader) {
        if (appSecret == null || appSecret.isBlank()) {
            if (allowUnsignedWebhooks) {
                log.warn("WhatsApp webhook accepted WITHOUT signature verification " +
                         "(app.whatsapp.allow-unsigned-webhooks=true) — never enable this in production");
                return true;
            }
            log.warn("WhatsApp webhook rejected: app.whatsapp.app-secret (WHATSAPP_APP_SECRET) is not configured");
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) return false;
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.substring("sha256=".length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody != null ? rawBody : new byte[0]);
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            log.error("WhatsApp webhook signature check failed: {}", e.toString());
            return false;
        }
    }

    /** True when some account uses this verify token (constant-time comparison). */
    public boolean isKnownVerifyToken(String verifyToken) {
        if (verifyToken == null || verifyToken.isBlank() || verifyToken.length() > 200) return false;
        byte[] given = verifyToken.getBytes(StandardCharsets.UTF_8);
        boolean match = false;
        for (WhatsAppAccount a : accountRepo.findByWebhookVerifyToken(verifyToken)) {
            String t = a.getWebhookVerifyToken();
            if (t != null && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), given)) match = true;
        }
        return match;
    }

    /** Processes a (signature-verified) webhook payload. Each event is isolated. */
    public void processWebhook(byte[] rawBody) throws Exception {
        JsonNode root = objectMapper.readTree(rawBody);
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                if (!"messages".equals(change.path("field").asText())) continue;
                JsonNode value = change.path("value");
                String phoneNumberId = value.path("metadata").path("phone_number_id").asText(null);
                if (phoneNumberId == null) continue;
                for (JsonNode msg : value.path("messages")) {
                    try { handleInbound(msg, phoneNumberId); }
                    catch (Exception e) { log.warn("WhatsApp inbound message failed: {}", e.toString()); }
                }
                for (JsonNode status : value.path("statuses")) {
                    try { handleStatusUpdate(status, phoneNumberId); }
                    catch (Exception e) { log.warn("WhatsApp status update failed: {}", e.toString()); }
                }
            }
        }
    }

    /**
     * Process a single inbound message event from the webhook payload.
     */
    public void handleInbound(JsonNode messageNode, String phoneNumberId) {
        WhatsAppAccount account = accountRepo.findByPhoneNumberId(phoneNumberId).orElse(null);
        if (account == null || !account.isActive()) {
            log.warn("No active WhatsApp account for phoneNumberId={}", phoneNumberId);
            return;
        }

        String from = messageNode.path("from").asText(null);
        String waId = messageNode.path("id").asText(null);
        String type = messageNode.path("type").asText("text");
        String body = messageNode.path("text").path("body").asText(null);

        // Meta retries deliveries — never store (or notify about) the same message twice.
        if (waId != null && messageRepo.existsByWaMessageIdAndOrganizationId(waId, account.getOrganizationId())) {
            return;
        }

        WhatsAppMessage msg = new WhatsAppMessage();
        msg.setOrganizationId(account.getOrganizationId());
        msg.setAccountId(account.getId());
        msg.setDirection("inbound");
        msg.setType(truncate(type, 20));
        msg.setFromNumber(truncate(from, 30));
        msg.setBody(body);
        msg.setWaMessageId(truncate(waId, 200));
        msg.setStatus("received");
        msg.setCreatedAt(Instant.now());
        msg.setUpdatedAt(Instant.now());

        messageRepo.save(msg);

        notificationService.createSystemNotification(
                account.getOrganizationId(),
                "whatsapp_reply",
                "New WhatsApp message from " + from,
                body != null ? truncate(body, 500) : "(media message)",
                "WhatsAppMessage"
        );
    }

    /**
     * Update delivery/read status from a webhook status event — scoped to the
     * organization that owns the receiving phone number.
     */
    public void handleStatusUpdate(JsonNode statusNode, String phoneNumberId) {
        String waId   = statusNode.path("id").asText(null);
        String status = statusNode.path("status").asText(null);
        if (waId == null || status == null || !STATUS_EVENTS.contains(status)) return;

        WhatsAppAccount account = accountRepo.findByPhoneNumberId(phoneNumberId).orElse(null);
        if (account == null) return;
        Long orgId = account.getOrganizationId();
        Instant now = Instant.now();

        messageRepo.findFirstByWaMessageIdAndOrganizationId(waId, orgId).ifPresent(msg -> {
            if (!advances(msg.getStatus(), status)) return;
            msg.setStatus(status);
            if ("failed".equals(status)) {
                JsonNode err = statusNode.path("errors").path(0);
                if (!err.isMissingNode()) {
                    msg.setErrorCode(truncate(err.path("code").asText(null), 50));
                    msg.setErrorMessage(truncate(err.path("title").asText(null), 500));
                }
            }
            msg.setUpdatedAt(now);
            messageRepo.save(msg);
        });

        contactRepo.findFirstByWaMessageIdAndOrganizationId(waId, orgId).ifPresent(cc -> {
            if (!advances(cc.getStatus(), status)) return;
            cc.setStatus(status);
            if ("delivered".equals(status) || "read".equals(status)) {
                if (cc.getDeliveredAt() == null) cc.setDeliveredAt(now);
            }
            if ("read".equals(status) && cc.getReadAt() == null) cc.setReadAt(now);
            if ("failed".equals(status)) {
                cc.setFailedReason(truncate(statusNode.path("errors").path(0).path("title").asText("Delivery failed"), 300));
            }
            contactRepo.save(cc);
            refreshCampaignCounts(cc.getCampaignId());
        });
    }

    /** Status only moves forward (webhooks can arrive out of order). */
    private static boolean advances(String current, String next) {
        int cur = rank(current);
        if ("failed".equals(next)) return cur < rank("delivered");
        return rank(next) > cur;
    }

    private static int rank(String s) {
        if (s == null) return 0;
        return switch (s) {
            case "sent" -> 1;
            case "delivered" -> 2;
            case "read" -> 3;
            case "failed" -> 4;
            default -> 0;   // pending | sending | received
        };
    }

    // ── Campaign dispatch ────────────────────────────────────────────────────

    /**
     * Queue a background dispatch of a running WhatsApp campaign. Inside a
     * transaction the job is submitted after commit so the worker sees the
     * new status; if the bounded queue is full the scheduler retries later.
     */
    public void dispatchCampaignAsync(Long campaignId) {
        Runnable submit = () -> {
            try {
                executor.submit("campaign-dispatch-" + campaignId, () -> dispatchCampaign(campaignId));
            } catch (TaskRejectedException e) {
                log.warn("Campaign {} dispatch deferred — executor queue full; scheduler will retry", campaignId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else {
            submit.run();
        }
    }

    /** Sends to every pending recipient while the campaign stays running. */
    void dispatchCampaign(Long campaignId) {
        if (!dispatching.add(campaignId)) return;   // already being dispatched in this JVM
        try {
            Campaign c = campaignRepo.findById(campaignId).orElse(null);
            if (c == null || !"running".equals(c.getStatus()) || !"whatsapp".equals(c.getType())) return;

            WhatsAppAccount account = resolveAccount(c);
            if (account == null || !account.isActive()) {
                log.warn("Campaign {} has no active WhatsApp account; recipients left pending", campaignId);
                return;
            }
            List<Map<String, Object>> components;
            String language;
            String templateName;
            try {
                components = parseTemplateComponents(c.getTemplateParams());
                language = validateLanguage(c.getTemplateLanguage());
                templateName = validateTemplateName(c.getTemplateName());
            } catch (IllegalArgumentException e) {
                log.warn("Campaign {} has invalid template settings: {}", campaignId, e.getMessage());
                return;
            }

            int sinceCheck = 0;
            outer:
            while (true) {
                List<CampaignContact> batch = contactRepo.findByCampaignIdAndStatusOrderByIdAsc(
                        campaignId, "pending", PageRequest.of(0, DISPATCH_BATCH));
                if (batch.isEmpty()) break;
                for (CampaignContact cc : batch) {
                    // Honour pause/cancel promptly without a query per recipient.
                    if (sinceCheck++ % 20 == 0
                            && !"running".equals(campaignRepo.findStatusById(campaignId).orElse(null))) {
                        break outer;
                    }
                    if (contactRepo.claimForSending(cc.getId()) != 1) continue;   // someone else took it
                    sendToContact(c, account, templateName, language, components, cc);
                }
            }
        } finally {
            dispatching.remove(campaignId);
            refreshCampaignCounts(campaignId);
            completeIfDone(campaignId);
        }
    }

    private void sendToContact(Campaign c, WhatsAppAccount account, String templateName, String language,
                               List<Map<String, Object>> components, CampaignContact cc) {
        try {
            if (!isValidPhone(cc.getPhoneNumber())) {
                cc.setStatus("failed");
                cc.setFailedReason("Invalid phone number");
            } else {
                WhatsAppMessage msg = sendTemplate(account, normalizePhone(cc.getPhoneNumber()), templateName,
                        language, components, "Campaign", c.getId(), null, c.getId());
                boolean failed = "failed".equals(msg.getStatus());
                cc.setStatus(failed ? "failed" : "sent");
                cc.setWaMessageId(msg.getWaMessageId());
                if (failed) cc.setFailedReason(truncate(msg.getErrorMessage(), 300));
            }
        } catch (Exception e) {
            log.warn("Campaign {} recipient {} failed: {}", c.getId(), cc.getId(), e.toString());
            cc.setStatus("failed");
            cc.setFailedReason("Send failed");
        }
        cc.setSentAt(Instant.now());
        contactRepo.save(cc);
    }

    private WhatsAppAccount resolveAccount(Campaign c) {
        if (c.getWaAccountId() != null) {
            return accountRepo.findByIdAndOrganizationId(c.getWaAccountId(), c.getOrganizationId()).orElse(null);
        }
        return accountRepo.findFirstByOrganizationIdAndActiveTrue(c.getOrganizationId()).orElse(null);
    }

    /** Recomputes campaign counters from recipient rows (cumulative: read ⊂ delivered ⊂ sent). */
    public void refreshCampaignCounts(Long campaignId) {
        if (campaignId == null) return;
        int sent      = (int) contactRepo.countByCampaignIdAndStatusIn(campaignId, List.of("sent", "delivered", "read"));
        int delivered = (int) contactRepo.countByCampaignIdAndStatusIn(campaignId, List.of("delivered", "read"));
        int read      = (int) contactRepo.countByCampaignIdAndStatus(campaignId, "read");
        int failed    = (int) contactRepo.countByCampaignIdAndStatus(campaignId, "failed");
        campaignRepo.updateCounts(campaignId, sent, delivered, read, failed, Instant.now());
    }

    private void completeIfDone(Long campaignId) {
        long open = contactRepo.countByCampaignIdAndStatusIn(campaignId, List.of("pending", "sending"));
        if (open == 0) campaignRepo.markCompleted(campaignId, Instant.now());
    }

    /**
     * Every minute: start scheduled campaigns that are due, and re-dispatch
     * running WhatsApp campaigns that still have pending recipients (e.g. after
     * a restart or a rejected executor submission). Per-recipient claiming
     * makes overlapping dispatches safe.
     */
    @Scheduled(fixedDelayString = "${app.campaigns.scheduler-delay-ms:60000}",
               initialDelayString = "${app.campaigns.scheduler-initial-delay-ms:30000}")
    @SchedulerLock(name = "campaigns.runDue", lockAtMostFor = "PT10M")
    public void runDueCampaigns() {
        try {
            Instant now = Instant.now();
            for (Campaign c : campaignRepo.findByStatusAndScheduledAtLessThanEqual("scheduled", now)) {
                if (campaignRepo.markScheduledRunning(c.getId(), now) == 1 && "whatsapp".equals(c.getType())) {
                    log.info("Scheduled campaign {} started", c.getId());
                    dispatchCampaignAsync(c.getId());
                }
            }
            for (Campaign c : campaignRepo.findByStatusAndType("running", "whatsapp")) {
                if (!dispatching.contains(c.getId())
                        && contactRepo.countByCampaignIdAndStatus(c.getId(), "pending") > 0) {
                    dispatchCampaignAsync(c.getId());
                }
            }
        } catch (Exception e) {
            log.error("Campaign scheduler run failed: {}", e.toString(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
