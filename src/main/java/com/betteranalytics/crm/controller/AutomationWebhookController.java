package com.betteranalytics.crm.controller;

import com.betteranalytics.crm.entity.AiAutomation;
import com.betteranalytics.crm.repository.AiAutomationRepository;
import com.betteranalytics.crm.service.AiAutomationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Inbound trigger for "webhook" automations. Public (no JWT) — authenticated by
 * a per-automation HMAC secret instead:
 *
 *   POST /api/automations/{id}/hook
 *   X-Timestamp: <unix seconds>                (must be within ±5 minutes)
 *   X-Signature: sha256=<hex HMAC-SHA256(secret, X-Timestamp + "." + rawBody)>
 *
 * Any failure (unknown id, inactive, wrong trigger, bad/old signature) is the
 * same 401 so the endpoint never reveals which automations exist. A verified
 * call queues the run on the bounded executor and returns 202 immediately; the
 * JSON body's top-level fields become {{variables}} (nested values as JSON).
 */
@RestController
@RequestMapping("/api/automations")
public class AutomationWebhookController {

    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_KEYS = 100;
    private static final int MAX_VALUE = 10_000;

    private final AiAutomationRepository automationRepo;
    private final AiAutomationService automationService;
    private final ObjectMapper objectMapper;

    public AutomationWebhookController(AiAutomationRepository automationRepo,
                                       AiAutomationService automationService,
                                       ObjectMapper objectMapper) {
        this.automationRepo = automationRepo;
        this.automationService = automationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{id}/hook")
    public ResponseEntity<Map<String, Object>> hook(@PathVariable Long id,
                                                    @RequestBody(required = false) byte[] rawBody,
                                                    @RequestHeader(value = "X-Signature", required = false) String signature,
                                                    @RequestHeader(value = "X-Timestamp", required = false) String timestamp) {
        byte[] body = rawBody != null ? rawBody : new byte[0];
        if (body.length > MAX_BODY) {
            return ResponseEntity.status(413).body(Map.of("error", "Payload too large", "code", "PAYLOAD_TOO_LARGE"));
        }
        AiAutomation a = automationRepo.findById(id).orElse(null);
        boolean ok = a != null && a.isActive() && "webhook".equals(a.getTriggerType())
                && AiAutomationService.verifyWebhookSignature(a.getWebhookSecret(), timestamp, body, signature, Instant.now());
        if (!ok) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired signature", "code", "INVALID_SIGNATURE"));
        }
        Map<String, String> ctx = context(body);
        ctx.put("webhook.receivedAt", Instant.now().toString());
        automationService.runAsync(a.getId(), a.getOrganizationId(), ctx, "webhook");
        return ResponseEntity.accepted().body(Map.of("accepted", true));
    }

    private Map<String, String> context(byte[] body) {
        Map<String, String> ctx = new HashMap<>();
        if (body.length == 0) return ctx;
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root != null && root.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                while (it.hasNext() && ctx.size() < MAX_KEYS) {
                    Map.Entry<String, JsonNode> e = it.next();
                    JsonNode v = e.getValue();
                    String s = v.isValueNode() ? v.asText() : v.toString();
                    ctx.put(e.getKey(), s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) : s);
                }
                return ctx;
            }
        } catch (Exception ignored) {
            // not JSON — fall through and expose the raw text
        }
        String raw = new String(body, StandardCharsets.UTF_8);
        ctx.put("body", raw.length() > MAX_VALUE ? raw.substring(0, MAX_VALUE) : raw);
        return ctx;
    }
}
