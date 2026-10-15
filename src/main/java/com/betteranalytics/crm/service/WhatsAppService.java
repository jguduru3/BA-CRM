package com.betteranalytics.crm.service;

import com.betteranalytics.crm.config.IntegrationAsyncConfig;
import com.betteranalytics.crm.entity.WhatsAppAccount;
import com.betteranalytics.crm.entity.WhatsAppConsent;
import com.betteranalytics.crm.entity.WhatsAppMessage;
import com.betteranalytics.crm.repository.CampaignContactRepository;
import com.betteranalytics.crm.repository.WhatsAppAccountRepository;
import com.betteranalytics.crm.repository.WhatsAppMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
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
 * Thin wrapper around Meta's WhatsApp Cloud API (Graph API v19.0) plus webhook
 * processing. Campaign dispatch lives in {@link CampaignDispatcher}.
 *
 * Nothing here runs inside a DB transaction: every Graph API call happens
 * first (5s connect / 30s read timeout) and the resulting row is then saved
 * by the repository in its own short transaction.
 *
 * To wire up: set WHATSAPP_GRAPH_URL (default https://graph.facebook.com/v19.0),
 * WHATSAPP_APP_SECRET (verifies X-Hub-Signature-256 on webhooks) and add one
 * or more WhatsAppAccount rows (sender numbers) per organization.
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
    public static final String DEFAULT_LANGUAGE = "en_US";

    private final WhatsAppAccountRepository  accountRepo;
    private final WhatsAppMessageRepository  messageRepo;
    private final CampaignContactRepository  contactRepo;
    private final RestTemplate               restTemplate;
    private final ObjectMapper               objectMapper;
    private final NotificationService        notificationService;
    private final WhatsAppConsentService     consentService;
    private final WhatsAppRecordResolver     recordResolver;
    private final CampaignDispatcher         campaignDispatcher;

    @Value("${app.whatsapp.graph-url:https://graph.facebook.com/v19.0}")
    private String graphUrl;

    @Value("${app.whatsapp.app-secret:}")
    private String appSecret;

    @Value("${app.whatsapp.allow-unsigned-webhooks:false}")
    private boolean allowUnsignedWebhooks;

    public WhatsAppService(WhatsAppAccountRepository accountRepo,
                           WhatsAppMessageRepository messageRepo,
                           CampaignContactRepository contactRepo,
                           NotificationService notificationService,
                           WhatsAppConsentService consentService,
                           WhatsAppRecordResolver recordResolver,
                           @Lazy CampaignDispatcher campaignDispatcher,
                           ObjectMapper objectMapper) {
        this.accountRepo = accountRepo;
        this.messageRepo = messageRepo;
        this.contactRepo = contactRepo;
        this.notificationService = notificationService;
        this.consentService = consentService;
        this.recordResolver = recordResolver;
        this.campaignDispatcher = campaignDispatcher;
        this.objectMapper = objectMapper;
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

    /** Free-form text to a number. Opted-out numbers → IllegalStateException (409), nothing is sent. */
    public WhatsAppMessage sendText(Long orgId, Long accountId, String toPhone, String text,
                                    String relatedType, Long relatedId, Long sentBy) {
        WhatsAppAccount account = requireActiveAccount(orgId, accountId);
        String phone = normalizePhone(toPhone);
        consentService.requireNotOptedOut(orgId, PhoneNumbers.normalize(phone));

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", phone,
                "type", "text",
                "text", Map.of("preview_url", false, "body", text)
        );
        return doSend(account, phone, "text", text, null, null, payload, relatedType, relatedId, sentBy, null);
    }

    /** Approved template to a number. Opted-out numbers → IllegalStateException (409). */
    public WhatsAppMessage sendTemplate(Long orgId, Long accountId, String toPhone,
                                        String templateName, String language,
                                        List<Map<String, Object>> components,
                                        String relatedType, Long relatedId, Long sentBy) {
        WhatsAppAccount account = requireActiveAccount(orgId, accountId);
        String phone = normalizePhone(toPhone);
        consentService.requireNotOptedOut(orgId, PhoneNumbers.normalize(phone));
        return sendTemplate(account, phone, validateTemplateName(templateName),
                validateLanguage(language), components, relatedType, relatedId, sentBy, null);
    }

    /** Campaign path: consent was already checked by the dispatcher for this recipient. */
    WhatsAppMessage sendCampaignTemplate(WhatsAppAccount account, String phone, String templateName,
                                         String language, List<Map<String, Object>> components,
                                         Long campaignId) {
        return sendTemplate(account, phone, templateName, language, components,
                "Campaign", campaignId, null, campaignId);
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

    public WhatsAppAccount requireActiveAccount(Long orgId, Long accountId) {
        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        WhatsAppAccount account = accountRepo.findByIdAndOrganizationId(accountId, orgId)
                .orElseThrow(() -> new NoSuchElementException("WhatsApp account not found"));
        if (!account.isActive()) throw new IllegalStateException("WhatsApp account is inactive");
        return account;
    }

    /** HTTP call first (no transaction open), then one short save. */
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
        msg.setNormalizedPhone(PhoneNumbers.tryNormalize(toPhone));
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
     * One inbound message. The receiving account (and so the organization) is
     * resolved from metadata.phone_number_id. The sender is linked to the Lead /
     * Contact with that number, so its owner can read the conversation; a bare
     * STOP / UNSUBSCRIBE / START updates consent.
     */
    public void handleInbound(JsonNode messageNode, String phoneNumberId) {
        WhatsAppAccount account = accountRepo.findByPhoneNumberId(phoneNumberId).orElse(null);
        if (account == null || !account.isActive()) {
            log.warn("No active WhatsApp account for phoneNumberId={}", phoneNumberId);
            return;
        }
        Long orgId = account.getOrganizationId();

        String from = messageNode.path("from").asText(null);
        String waId = messageNode.path("id").asText(null);
        String type = messageNode.path("type").asText("text");
        String body = messageNode.path("text").path("body").asText(null);
        if (body == null) body = messageNode.path("button").path("text").asText(null);

        // Meta retries deliveries — never store (or notify about) the same message twice.
        if (waId != null && messageRepo.existsByWaMessageIdAndOrganizationId(waId, orgId)) {
            return;
        }

        String normalized = PhoneNumbers.tryNormalize(from);
        WhatsAppRecordResolver.Link link = recordResolver.findByPhone(orgId, normalized).orElse(null);

        WhatsAppMessage msg = new WhatsAppMessage();
        msg.setOrganizationId(orgId);
        msg.setAccountId(account.getId());
        msg.setDirection("inbound");
        msg.setType(truncate(type, 20));
        msg.setFromNumber(truncate(from, 30));
        msg.setToNumber(truncate(account.getDisplayPhoneNumber(), 30));
        msg.setNormalizedPhone(normalized);
        msg.setBody(body);
        msg.setWaMessageId(truncate(waId, 200));
        msg.setStatus("received");
        if (link != null) {
            msg.setRelatedType(link.relatedType());
            msg.setRelatedId(link.relatedId());
        }
        msg.setCreatedAt(Instant.now());
        msg.setUpdatedAt(Instant.now());
        msg = messageRepo.save(msg);

        String keyword = WhatsAppConsentService.keywordStatus(body);
        if (keyword != null && normalized != null) {
            consentService.record(orgId, normalized, keyword, WhatsAppConsentService.SOURCE_KEYWORD,
                    "Subscriber replied \"" + truncate(body.trim(), 20) + "\"", null, msg.getId());
        }

        String title = keyword == null ? "New WhatsApp message from " + from
                : WhatsAppConsent.OPTED_OUT.equals(keyword) ? "WhatsApp opt-out from " + from
                : "WhatsApp opt-in from " + from;
        String preview = body != null ? truncate(body, 500) : "(media message)";
        notificationService.createSystemNotification(orgId, "whatsapp_reply", title, preview, "WhatsAppMessage");
        if (link != null && link.ownerId() != null) {
            notificationService.notify(orgId, link.ownerId(), "whatsapp_reply", title, preview,
                    link.relatedType(), link.relatedId());
        }
    }

    /**
     * Delivery status (sent → delivered → read, or failed) from a status webhook,
     * scoped to the organization that owns the receiving phone number. Updates
     * the message row and, for campaign sends, the recipient row (compare-and-set
     * on the current status, so it never races the dispatcher).
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
            if (("delivered".equals(status) || "read".equals(status)) && msg.getDeliveredAt() == null) msg.setDeliveredAt(now);
            if ("read".equals(status) && msg.getReadAt() == null) msg.setReadAt(now);
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
            String reason = "failed".equals(status)
                    ? truncate(statusNode.path("errors").path(0).path("title").asText("Delivery failed"), 300) : null;
            int updated = contactRepo.advanceDeliveryStatus(cc.getId(), cc.getStatus(), status,
                    ("delivered".equals(status) || "read".equals(status)) ? now : null,
                    "read".equals(status) ? now : null, reason, now);
            if (updated == 1) campaignDispatcher.refreshCounts(cc.getCampaignId());
        });
    }

    /** Status only moves forward (webhooks can arrive out of order). */
    static boolean advances(String current, String next) {
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
