package com.betteranalytics.crm.engagement;

import com.betteranalytics.crm.entity.AiAutomationRun;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AiAutomationRepository;
import com.betteranalytics.crm.repository.AiAutomationRunRepository;
import com.betteranalytics.crm.service.AiAutomationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Automations: AI_AUTOMATION permissions, cron + webhook + campaign-approved triggers, run log. */
class AutomationTriggersTest extends EngagementTestSupport {

    @Autowired AiAutomationRepository automationRepository;
    @Autowired AiAutomationRunRepository runRepository;
    @Autowired AiAutomationService automationService;

    private Map<String, Object> def(String trigger, String triggerConfig) {
        return Map.of("name", "Auto " + trigger, "triggerType", trigger,
                "triggerConfigJson", triggerConfig == null ? "" : triggerConfig,
                "actionType", "generate_content",
                "actionConfigJson", "{\"promptTemplate\":\"Summarise {{topic}}\"}");
    }

    private List<AiAutomationRun> runs(long automationId, Long org) {
        return runRepository.findByAutomationIdAndOrganizationIdOrderByStartedAtDesc(automationId, org, PageRequest.of(0, 50));
    }

    private ResultActions hook(long id, String body, String secret, long ts) throws Exception {
        String sig = secret == null ? "sha256=00" : "sha256=" + HexFormat.of().formatHex(
                AiAutomationService.webhookSignature(secret, String.valueOf(ts), body.getBytes(StandardCharsets.UTF_8)));
        return mvc.perform(post("/api/automations/" + id + "/hook").contentType(MediaType.APPLICATION_JSON)
                .content(body).header("X-Timestamp", String.valueOf(ts)).header("X-Signature", sig));
    }

    @Test
    void permissionsGoThroughAiAutomation() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        long id = idOf(postAs(manager, "/api/ai-automations", def("manual", null)).andExpect(status().isCreated()));

        getAs(user(org, Role.EXECUTIVE), "/api/ai-automations").andExpect(status().isForbidden());
        getAs(user(org, Role.SUPPORT), "/api/ai-automations").andExpect(status().isForbidden());
        postAs(user(org, Role.EXECUTIVE), "/api/ai-automations", def("manual", null)).andExpect(status().isForbidden());
        getAs(user(newOrg(), Role.SUPER_ADMIN), "/api/ai-automations/" + id).andExpect(status().isNotFound());
        deleteAs(user(newOrg(), Role.SUPER_ADMIN), "/api/ai-automations/" + id).andExpect(status().isNotFound());

        // Email / custom API actions stay VP / Super Admin only.
        postAs(manager, "/api/ai-automations", Map.of("name", "x", "triggerType", "manual", "actionType", "custom_api",
                "actionConfigJson", "{\"url\":\"https://example.com/x\"}")).andExpect(status().isForbidden());

        // Write-only API key: hint + flag back, never the key; clearApiKey removes it.
        JsonNode updated = body(putAs(manager, "/api/ai-automations/" + id, Map.of("apiKey", "sk-ant-test-abcd9876"))
                .andExpect(status().isOk()));
        assertThat(updated.path("apiKeySet").asBoolean()).isTrue();
        assertThat(updated.path("apiKeyHint").asText()).endsWith("9876");
        assertThat(updated.toString()).doesNotContain("sk-ant-test-abcd9876");
        assertThat(body(putAs(manager, "/api/ai-automations/" + id, Map.of("clearApiKey", true)))
                .path("apiKeySet").asBoolean()).isFalse();

        // Manual run without a key fails cleanly and is logged with a duration.
        JsonNode run = body(postAs(manager, "/api/ai-automations/" + id + "/run", Map.of("context", Map.of("topic", "Q3")))
                .andExpect(status().isOk()));
        assertThat(run.path("status").asText()).isEqualTo("failed");
        assertThat(run.path("errorMessage").asText()).contains("No API key");
        assertThat(run.path("durationMs").isNumber()).isTrue();
        assertThat(run.path("triggerSource").asText()).isEqualTo("manual");
    }

    @Test
    void scheduledTriggerFiresFromCron() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        postAs(vp, "/api/ai-automations", def("scheduled", "{\"cron\":\"not a cron\"}")).andExpect(status().isBadRequest());
        postAs(vp, "/api/ai-automations", def("scheduled", null)).andExpect(status().isBadRequest());

        JsonNode created = body(postAs(vp, "/api/ai-automations", def("scheduled", "{\"cron\":\"0 9 * * *\",\"timezone\":\"Asia/Kolkata\"}"))
                .andExpect(status().isCreated()));
        long id = created.path("id").asLong();
        assertThat(created.path("nextRunAt").isTextual()).isTrue();

        // Not due yet → nothing queued; a day later → exactly one run, and not again for the same slot.
        Instant now = Instant.now();
        automationService.fireDueScheduled(now);
        assertThat(runs(id, org)).isEmpty();
        Instant later = now.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(1));
        automationService.fireDueScheduled(later);
        await("scheduled run logged", () -> runs(id, org).size() == 1 && runs(id, org).get(0).getCompletedAt() != null);
        automationService.fireDueScheduled(later);
        Thread.sleep(300);
        assertThat(runs(id, org)).hasSize(1);
        AiAutomationRun r = runs(id, org).get(0);
        assertThat(r.getTriggerSource()).isEqualTo("scheduled");
        assertThat(r.getStatus()).isEqualTo("failed");          // no API key configured in tests
        assertThat(r.getDurationMs()).isNotNull();
        assertThat(automationRepository.findById(id).orElseThrow().getScheduleLastFiredAt()).isNotNull();
    }

    @Test
    void webhookTriggerVerifiesHmacSecretShownOnce() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        JsonNode created = body(postAs(manager, "/api/ai-automations", def("webhook", null)).andExpect(status().isCreated()));
        long id = created.path("id").asLong();
        String secret = created.path("webhookSecret").asText();
        assertThat(secret).startsWith("whsec_");
        assertThat(created.path("hookPath").asText()).isEqualTo("/api/automations/" + id + "/hook");

        JsonNode fetched = body(getAs(manager, "/api/ai-automations/" + id));
        assertThat(fetched.has("webhookSecret")).isFalse();
        assertThat(fetched.path("webhookSecretSet").asBoolean()).isTrue();
        assertThat(fetched.toString()).doesNotContain(secret);

        String payload = "{\"topic\":\"renewals\",\"count\":3}";
        long now = Instant.now().getEpochSecond();
        hook(id, payload, "whsec_wrong", now).andExpect(status().isUnauthorized());
        hook(id, payload, secret, now - 600).andExpect(status().isUnauthorized());          // too old
        hook(id + 100_000, payload, secret, now).andExpect(status().isUnauthorized());     // unknown id
        mvc.perform(post("/api/automations/" + id + "/hook").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());                                       // no headers
        assertThat(runs(id, org)).isEmpty();

        hook(id, payload, secret, now).andExpect(status().isAccepted());
        await("webhook run", () -> runs(id, org).size() == 1 && runs(id, org).get(0).getCompletedAt() != null);
        AiAutomationRun r = runs(id, org).get(0);
        assertThat(r.getTriggerSource()).isEqualTo("webhook");
        assertThat(r.getTriggerPayloadJson()).contains("renewals");

        // Rotation: the old secret stops working, the new one is returned once.
        String rotated = body(postAs(manager, "/api/ai-automations/" + id + "/webhook-secret", Map.of())
                .andExpect(status().isOk())).path("webhookSecret").asText();
        assertThat(rotated).isNotEqualTo(secret);
        hook(id, payload, secret, now).andExpect(status().isUnauthorized());
        hook(id, payload, rotated, now).andExpect(status().isAccepted());

        // A non-webhook automation can't be triggered this way.
        long manual = idOf(postAs(manager, "/api/ai-automations", def("manual", null)));
        hook(manual, payload, rotated, now).andExpect(status().isUnauthorized());
    }

    @Test
    void campaignApprovedTriggerRunsAfterApproval() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        User admin = user(org, Role.SUPER_ADMIN);
        long autoId = idOf(postAs(vp, "/api/ai-automations", def("on_campaign_approved", null)).andExpect(status().isCreated()));
        long campaign = idOf(postAs(vp, "/api/campaigns", Map.of("name", "Launch", "templateName", "launch_tpl")));
        postAs(vp, "/api/campaigns/" + campaign + "/submit", Map.of()).andExpect(status().isOk());
        assertThat(runs(autoId, org)).isEmpty();
        postAs(admin, "/api/campaigns/" + campaign + "/approve", Map.of()).andExpect(status().isOk());

        await("campaign-approved run", () -> runs(autoId, org).size() == 1 && runs(autoId, org).get(0).getCompletedAt() != null);
        AiAutomationRun r = runs(autoId, org).get(0);
        assertThat(r.getTriggerSource()).isEqualTo("event");
        assertThat(r.getTriggerPayloadJson()).contains("\"campaign.id\":\"" + campaign + "\"");
    }
}
