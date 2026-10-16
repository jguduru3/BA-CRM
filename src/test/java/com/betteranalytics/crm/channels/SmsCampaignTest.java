package com.betteranalytics.crm.channels;

import com.betteranalytics.crm.entity.Campaign;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.service.SmsSender;
import com.betteranalytics.crm.service.WhatsAppConsentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SMS campaigns through the (faked) Azure Communication Services gateway + Event Grid STOP handling. */
class SmsCampaignTest extends ChannelTestSupport {

    @Autowired WhatsAppConsentService consentService;

    @Test
    void launchIs409WhenSmsIsNotConfigured() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        long id = idOf(postAs(admin, "/api/campaigns", Map.of("name", "Flash sale", "type", "sms",
                "messageBody", "Sale today, reply STOP to opt out")).andExpect(status().isOk()));
        postAs(admin, "/api/campaigns/" + id + "/contacts", List.of(Map.of("phone", uniquePhone()))).andExpect(status().isOk());
        postAs(admin, "/api/campaigns/" + id + "/launch", Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SMS is not configured"));
        JsonNode channels = body(getAs(admin, "/api/campaigns/channels").andExpect(status().isOk()));
        assertThat(channels.path("sms").path("configured").asBoolean()).isFalse();

        long empty = idOf(postAs(admin, "/api/campaigns", Map.of("name", "e", "type", "sms")).andExpect(status().isOk()));
        postAs(admin, "/api/campaigns/" + empty + "/submit", Map.of()).andExpect(status().isBadRequest());
        postAs(admin, "/api/campaigns", Map.of("name", "long", "type", "sms", "messageBody", "x".repeat(1601)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendsEachNumberOnceSkipsOptOutsAndRetriesWhenTheGatewayIsDown() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        when(smsSender.isConfigured()).thenReturn(true);
        when(smsSender.fromNumber()).thenReturn("+18005550100");
        Map<String, String> sentText = new ConcurrentHashMap<>();
        Map<String, Integer> calls = new ConcurrentHashMap<>();
        when(smsSender.send(anyString(), anyString(), any())).thenAnswer(inv -> {
            String to = inv.getArgument(0);
            calls.merge(to, 1, Integer::sum);
            sentText.put(to, inv.getArgument(1));
            return new SmsSender.Result(SmsSender.Outcome.SENT, "msg-" + to, "Accepted");
        });

        long id = idOf(postAs(admin, "/api/campaigns", Map.of("name", "Flash sale", "type", "sms",
                "messageBody", "Hi {{contact.firstName}}, sale today. Reply STOP to opt out")).andExpect(status().isOk()));
        String p1 = uniquePhone(), p2 = uniquePhone(), stop = uniquePhone();
        postAs(admin, "/api/campaigns/" + id + "/contacts", List.of(
                Map.of("phone", p1, "name", "Ravi Kumar"), Map.of("phone", p2), Map.of("phone", stop),
                Map.of("phone", p1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(3)).andExpect(jsonPath("$.duplicates").value(1));
        consentService.record(org, stop, "opted_out", "manual", "replied STOP", admin.getId(), null);

        JsonNode channels = body(getAs(admin, "/api/campaigns/channels").andExpect(status().isOk()));
        assertThat(channels.path("sms").path("configured").asBoolean()).isTrue();
        assertThat(channels.path("sms").path("from").asText()).isEqualTo("+18005550100");

        postAs(admin, "/api/campaigns/" + id + "/launch", Map.of()).andExpect(status().isOk());
        await("campaign completed", () -> "completed".equals(campaignStatus(id)));
        assertThat(calls).containsOnlyKeys(p1, p2).containsEntry(p1, 1).containsEntry(p2, 1);
        assertThat(sentText.get(p1)).isEqualTo("Hi Ravi, sale today. Reply STOP to opt out");
        Map<String, String> byPhone = new HashMap<>();
        recipientRepository.findByCampaignId(id).forEach(cc -> byPhone.put(cc.getNormalizedPhone(), cc.getStatus()));
        assertThat(byPhone).containsEntry(p1, "sent").containsEntry(p2, "sent").containsEntry(stop, "skipped_opted_out");
        assertThat(campaignRepository.findById(id).orElseThrow().getSentCount()).isEqualTo(2);

        // Gateway down (NOT_SENT): recipients stay pending; a permanent rejection fails only that number.
        Campaign c = sendingCampaign(org, admin.getId(), "sms", null);
        String down = uniquePhone(), rejected = uniquePhone();
        smsRecipient(c, down);
        when(smsSender.send(anyString(), anyString(), any())).thenAnswer(inv ->
                new SmsSender.Result(SmsSender.Outcome.NOT_SENT, null, "SMS gateway busy (HTTP 503)"));
        dispatcher.dispatch(c.getId());
        assertThat(recipientRepository.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(1);
        assertThat(campaignStatus(c.getId())).isEqualTo("sending");

        smsRecipient(c, rejected);
        when(smsSender.send(anyString(), anyString(), any())).thenAnswer(inv -> rejected.equals(inv.getArgument(0))
                ? new SmsSender.Result(SmsSender.Outcome.FAILED_PERMANENT, null, "Rejected: invalid number")
                : new SmsSender.Result(SmsSender.Outcome.SENT, "m1", "Accepted"));
        dispatcher.dispatch(c.getId());
        assertThat(campaignStatus(c.getId())).isEqualTo("completed");
        Map<String, String> after = new HashMap<>();
        recipientRepository.findByCampaignId(c.getId()).forEach(cc -> after.put(cc.getNormalizedPhone(), cc.getStatus()));
        assertThat(after).containsEntry(down, "sent").containsEntry(rejected, "failed");
    }

    @Test
    void eventGridWebhookValidatesAndMapsStopToConsent() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        Campaign c = sendingCampaign(org, admin.getId(), "sms", null);
        String phone = uniquePhone();
        smsRecipient(c, phone);
        c.setStatus("completed");
        campaignRepository.save(c);

        String validation = """
                [{"id":"1","eventType":"Microsoft.EventGrid.SubscriptionValidationEvent",
                  "data":{"validationCode":"512d38b6-c7b8-40c8-89fe-f46f9e9622b6"}}]""";
        mvc.perform(post("/api/sms/events").param("secret", "wrong").contentType(MediaType.APPLICATION_JSON).content(validation))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sms/events").contentType(MediaType.APPLICATION_JSON).content(validation))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/sms/events").param("secret", "test-sms-events-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(validation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validationResponse").value("512d38b6-c7b8-40c8-89fe-f46f9e9622b6"));

        String stop = """
                [{"id":"2","eventType":"Microsoft.Communication.SMSReceived",
                  "data":{"from":"%s","to":"+18005550100","message":" stop "}}]""".formatted(phone);
        mvc.perform(post("/api/sms/events").param("secret", "test-sms-events-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(stop))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processed").value(1));
        assertThat(consentService.isOptedOut(org, phone)).isTrue();
        assertThat(consentService.isOptedOut(newOrg(), phone)).isFalse();

        String start = stop.replace(" stop ", "START");
        mvc.perform(post("/api/sms/events").param("secret", "test-sms-events-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(start))
                .andExpect(status().isOk());
        assertThat(consentService.isOptedOut(org, phone)).isFalse();

        String chatter = stop.replace(" stop ", "Is the sale still on?");
        mvc.perform(post("/api/sms/events").param("secret", "test-sms-events-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(chatter))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processed").value(0));
    }
}
