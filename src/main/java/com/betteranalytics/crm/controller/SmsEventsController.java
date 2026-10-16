package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.WhatsAppConsent;
import com.betteranalytics.crm.repository.CampaignContactRepository;
import com.betteranalytics.crm.service.PhoneNumbers;
import com.betteranalytics.crm.service.WhatsAppConsentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Inbound SMS from Azure Communication Services, delivered by an Event Grid
 * webhook subscription (Event Grid schema) to
 *   POST /api/sms/events?secret=&lt;app.sms.events-secret&gt;
 *
 *   - Microsoft.EventGrid.SubscriptionValidationEvent → {"validationResponse": code}
 *     (the handshake Event Grid performs when the subscription is created);
 *   - Microsoft.Communication.SMSReceived → a STOP / START keyword (same words as
 *     WhatsApp) updates the shared phone consent store for every organization that
 *     has sent an SMS campaign to that number (the sender number is platform-wide).
 *
 * Disabled (404) while app.sms.events-secret (AZURE_SMS_EVENTS_SECRET) is blank;
 * a wrong secret is 401. Without this subscription inbound STOP replies are not
 * seen — opt-outs then have to be recorded manually.
 */
@RestController
@RequestMapping("/api/sms")
public class SmsEventsController {

    private static final Logger log = LoggerFactory.getLogger(SmsEventsController.class);
    private static final int MAX_EVENTS = 100;

    private final WhatsAppConsentService consentService;
    private final CampaignContactRepository contactRepo;
    private final ObjectMapper objectMapper;
    private final String secret;

    public SmsEventsController(WhatsAppConsentService consentService, CampaignContactRepository contactRepo,
                               ObjectMapper objectMapper, @Value("${app.sms.events-secret:}") String secret) {
        this.consentService = consentService;
        this.contactRepo = contactRepo;
        this.objectMapper = objectMapper;
        this.secret = secret == null ? "" : secret.trim();
    }

    @PostMapping("/events")
    public ResponseEntity<?> events(@RequestParam(name = "secret", required = false) String provided,
                                    @RequestBody(required = false) String raw) {
        if (secret.isEmpty()) return ResponseEntity.notFound().build();
        if (provided == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid secret"));
        }
        JsonNode root;
        try {
            root = raw == null || raw.isBlank() ? null : objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("body must be an Event Grid event array");
        }
        if (root == null) throw new IllegalArgumentException("body must be an Event Grid event array");
        List<JsonNode> events = root.isArray() ? iterable(root) : List.of(root);
        if (events.size() > MAX_EVENTS) throw new IllegalArgumentException("too many events");

        int processed = 0;
        for (JsonNode ev : events) {
            String type = ev.path("eventType").asText("");
            JsonNode data = ev.path("data");
            if ("Microsoft.EventGrid.SubscriptionValidationEvent".equals(type)) {
                return ResponseEntity.ok(Map.of("validationResponse", data.path("validationCode").asText("")));
            }
            if ("Microsoft.Communication.SMSReceived".equals(type)) {
                if (handleInbound(data.path("from").asText(null), data.path("message").asText(null))) processed++;
            }
        }
        return ResponseEntity.ok(Map.of("processed", processed));
    }

    private boolean handleInbound(String from, String message) {
        String phone = PhoneNumbers.tryNormalize(from);
        String status = WhatsAppConsentService.keywordStatus(message);
        if (phone == null || status == null) return false;
        List<Long> orgs = contactRepo.findSmsOrganizationsByPhone(phone);
        for (Long org : orgs) {
            boolean optedOut = consentService.isOptedOut(org, phone);
            if (optedOut == WhatsAppConsent.OPTED_OUT.equals(status)) continue;   // already in that state
            consentService.record(org, phone, status, WhatsAppConsentService.SOURCE_KEYWORD,
                    "SMS reply: " + message.trim().toUpperCase(java.util.Locale.ROOT), null, null);
        }
        if (orgs.isEmpty()) log.info("Inbound SMS keyword from a number no SMS campaign has messaged; ignored");
        return !orgs.isEmpty();
    }

    private static List<JsonNode> iterable(JsonNode array) {
        java.util.ArrayList<JsonNode> out = new java.util.ArrayList<>();
        array.forEach(out::add);
        return out;
    }
}
