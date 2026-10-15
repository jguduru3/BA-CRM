package com.betteranalytics.crm.engagement;

import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.WhatsAppAccount;
import com.betteranalytics.crm.entity.WhatsAppMessage;
import com.betteranalytics.crm.repository.WhatsAppMessageRepository;
import com.betteranalytics.crm.service.WhatsAppConsentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Meta webhook: X-Hub-Signature-256, routing by phone_number_id, delivery statuses, STOP/START. */
class WhatsAppWebhookTest extends EngagementTestSupport {

    @Autowired WhatsAppMessageRepository messageRepository;
    @Autowired WhatsAppConsentService consentService;

    @Test
    void signatureIsRequiredAndVerified() throws Exception {
        WhatsAppAccount acct = offlineAccount(newOrg());
        String waId = "wamid." + UUID.randomUUID();
        String body = inboundPayload(acct.getPhoneNumberId(), "919800000001", waId, "hello");

        postWebhook(body, null).andExpect(status().isUnauthorized());
        postWebhook(body, "sha256=" + "00".repeat(32)).andExpect(status().isUnauthorized());
        postWebhook(body, "not-a-signature").andExpect(status().isUnauthorized());
        postWebhook(body, hubSignature(body + " ")).andExpect(status().isUnauthorized());   // body tampered
        assertThat(messageRepository.existsByWaMessageIdAndOrganizationId(waId, acct.getOrganizationId())).isFalse();

        postWebhook(body, hubSignature(body)).andExpect(status().isOk());
        assertThat(messageRepository.existsByWaMessageIdAndOrganizationId(waId, acct.getOrganizationId())).isTrue();

        // Meta retries: the same message is stored once.
        postWebhook(body, hubSignature(body)).andExpect(status().isOk());
        assertThat(messageRepository.findAll().stream().filter(m -> waId.equals(m.getWaMessageId())).count()).isEqualTo(1);
    }

    @Test
    void inboundIsRoutedByPhoneNumberIdAndLinkedToTheLeadOwner() throws Exception {
        Long orgA = newOrg();
        Long orgB = newOrg();
        WhatsAppAccount a = offlineAccount(orgA);
        offlineAccount(orgB);
        User owner = user(orgA, Role.EXECUTIVE);
        String phone = uniquePhone();
        // The lead's phone is stored with formatting; matching strips it in the query.
        Lead lead = lead(orgA, owner.getId(), phone.substring(0, 3) + " " + phone.substring(3, 8) + "-" + phone.substring(8), null);
        String waId = "wamid." + UUID.randomUUID();
        String body = inboundPayload(a.getPhoneNumberId(), phone.substring(1), waId, "Is the offer still open?");
        postWebhook(body, hubSignature(body)).andExpect(status().isOk());

        WhatsAppMessage stored = messageRepository.findFirstByWaMessageIdAndOrganizationId(waId, orgA).orElseThrow();
        assertThat(stored.getDirection()).isEqualTo("inbound");
        assertThat(stored.getNormalizedPhone()).isEqualTo(phone);
        assertThat(stored.getRelatedType()).isEqualTo("Lead");
        assertThat(stored.getRelatedId()).isEqualTo(lead.getId());
        assertThat(messageRepository.existsByWaMessageIdAndOrganizationId(waId, orgB)).isFalse();

        // The owner reads it; the owner got a notification.
        String thread = "/api/whatsapp/conversations/phone/" + phone;
        assertThat(body(getAs(owner, thread)).get(0).path("body").asText()).isEqualTo("Is the offer still open?");
        assertThat(body(getAs(owner, "/api/notifications")).findValuesAsText("type")).contains("whatsapp_reply");

        // Unknown phone_number_id → accepted (200, so Meta stops retrying) but stored nowhere.
        String stray = inboundPayload("unknown-pnid", "919811111111", "wamid.stray-" + UUID.randomUUID(), "x");
        postWebhook(stray, hubSignature(stray)).andExpect(status().isOk());
    }

    @Test
    void statusWebhooksMoveForwardOnly() throws Exception {
        Long org = newOrg();
        WhatsAppAccount acct = offlineAccount(org);
        WhatsAppMessage m = new WhatsAppMessage();
        m.setOrganizationId(org);
        m.setAccountId(acct.getId());
        m.setDirection("outbound");
        m.setType("text");
        m.setStatus("sent");
        m.setWaMessageId("wamid." + UUID.randomUUID());
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m = messageRepository.save(m);

        for (String s : new String[]{"read", "delivered", "failed"}) {
            String body = statusPayload(acct.getPhoneNumberId(), m.getWaMessageId(), s);
            postWebhook(body, hubSignature(body)).andExpect(status().isOk());
        }
        WhatsAppMessage after = messageRepository.findById(m.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("read");     // late 'delivered' / 'failed' ignored
        assertThat(after.getReadAt()).isNotNull();
        assertThat(after.getDeliveredAt()).isNotNull();

        // A status for the same wamid sent to another org's number does not cross tenants.
        WhatsAppAccount other = offlineAccount(newOrg());
        String body = statusPayload(other.getPhoneNumberId(), m.getWaMessageId(), "failed");
        postWebhook(body, hubSignature(body)).andExpect(status().isOk());
        assertThat(messageRepository.findById(m.getId()).orElseThrow().getStatus()).isEqualTo("read");
    }

    @Test
    void stopOptsOutAndStartOptsBackIn() throws Exception {
        Long org = newOrg();
        WhatsAppAccount acct = offlineAccount(org);
        String phone = uniquePhone();

        String stop = inboundPayload(acct.getPhoneNumberId(), phone.substring(1), "wamid." + UUID.randomUUID(), " stop ");
        postWebhook(stop, hubSignature(stop)).andExpect(status().isOk());
        assertThat(consentService.isOptedOut(org, phone)).isTrue();

        String start = inboundPayload(acct.getPhoneNumberId(), phone.substring(1), "wamid." + UUID.randomUUID(), "START");
        postWebhook(start, hubSignature(start)).andExpect(status().isOk());
        assertThat(consentService.isOptedOut(org, phone)).isFalse();

        String unsub = inboundPayload(acct.getPhoneNumberId(), phone.substring(1), "wamid." + UUID.randomUUID(), "Unsubscribe");
        postWebhook(unsub, hubSignature(unsub)).andExpect(status().isOk());
        assertThat(consentService.isOptedOut(org, phone)).isTrue();
        assertThat(consentService.history(org, phone, 10)).hasSize(3)
                .allMatch(e -> WhatsAppConsentService.SOURCE_KEYWORD.equals(e.getSource()) && e.getMessageId() != null);

        // "please stop calling me" is a message, not a keyword.
        assertThat(WhatsAppConsentService.keywordStatus("please stop calling me")).isNull();
    }
}
