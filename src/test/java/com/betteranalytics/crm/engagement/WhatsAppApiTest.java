package com.betteranalytics.crm.engagement;

import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.WhatsAppAccount;
import com.betteranalytics.crm.entity.WhatsAppMessage;
import com.betteranalytics.crm.repository.WhatsAppMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** WhatsApp endpoints: PermissionService (WHATSAPP), body field mask, accounts, consent. */
class WhatsAppApiTest extends EngagementTestSupport {

    @Autowired WhatsAppMessageRepository messageRepository;

    private WhatsAppMessage storedMessage(Long orgId, Long sentBy, String relatedType, Long relatedId, String phone) {
        WhatsAppMessage m = new WhatsAppMessage();
        m.setOrganizationId(orgId);
        m.setDirection(sentBy == null ? "inbound" : "outbound");
        m.setType("text");
        m.setBody("secret deal terms");
        m.setTemplateParams("[{\"x\":1}]");
        m.setStatus("delivered");
        m.setSentBy(sentBy);
        m.setRelatedType(relatedType);
        m.setRelatedId(relatedId);
        m.setToNumber(phone);
        m.setNormalizedPhone(phone);
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        return messageRepository.save(m);
    }

    @Test
    void accountManagementNeedsEditAndHidesSecrets() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User exec = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        Map<String, Object> acct = Map.of("phoneNumberId", "PN" + System.nanoTime(),
                "accessToken", "EAAG-super-secret-token-1234", "displayPhoneNumber", "+15550001111",
                "webhookVerifyToken", "verify-me");

        postAs(exec, "/api/whatsapp/accounts", acct).andExpect(status().isForbidden());
        postAs(support, "/api/whatsapp/accounts", acct).andExpect(status().isForbidden());

        JsonNode created = body(postAs(admin, "/api/whatsapp/accounts", acct).andExpect(status().isOk()));
        assertThat(created.has("accessToken")).isFalse();
        assertThat(created.path("hasAccessToken").asBoolean()).isTrue();
        assertThat(created.path("accessTokenHint").asText()).endsWith("1234");
        assertThat(created.path("webhookVerifyTokenSet").asBoolean()).isTrue();

        // Same Meta phone number id twice → 409, even from another organization.
        postAs(admin, "/api/whatsapp/accounts", acct).andExpect(status().isConflict());
        postAs(user(newOrg(), Role.SUPER_ADMIN), "/api/whatsapp/accounts", acct).andExpect(status().isConflict());

        // Senders (and read-only SUPPORT) see the sender list, without configuration details.
        JsonNode list = body(getAs(exec, "/api/whatsapp/accounts").andExpect(status().isOk()));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).has("phoneNumberId")).isFalse();
        assertThat(list.get(0).has("accessTokenHint")).isFalse();
        getAs(support, "/api/whatsapp/accounts").andExpect(status().isOk());

        long id = created.path("id").asLong();
        putAs(exec, "/api/whatsapp/accounts/" + id, Map.of("active", false)).andExpect(status().isForbidden());
        deleteAs(support, "/api/whatsapp/accounts/" + id).andExpect(status().isForbidden());

        // Another organization's admin cannot see or touch it.
        User otherAdmin = user(newOrg(), Role.SUPER_ADMIN);
        putAs(otherAdmin, "/api/whatsapp/accounts/" + id, Map.of("active", false)).andExpect(status().isNotFound());
        deleteAs(otherAdmin, "/api/whatsapp/accounts/" + id).andExpect(status().isNotFound());

        putAs(admin, "/api/whatsapp/accounts/" + id, Map.of("active", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void sendRequiresCreateAndSupportIsReadOnly() throws Exception {
        Long org = newOrg();
        WhatsAppAccount acct = offlineAccount(org);
        User exec = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        Map<String, Object> send = Map.of("accountId", acct.getId(), "phone", uniquePhone(), "text", "hi");

        postAs(support, "/api/whatsapp/send", send).andExpect(status().isForbidden());
        getAs(support, "/api/whatsapp/conversations").andExpect(status().isOk());

        // Executive may send; the offline account fails fast without a network call.
        postAs(exec, "/api/whatsapp/send", send).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.sentBy").value(exec.getId()));

        // Another org's account id is not usable.
        User foreign = user(newOrg(), Role.SUPER_ADMIN);
        postAs(foreign, "/api/whatsapp/send", send).andExpect(status().isNotFound());
    }

    @Test
    void messageBodiesAreMaskedForUsersWithoutAccess() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User owner = user(org, Role.EXECUTIVE);
        User sender = user(org, Role.FIELD_SALES);
        User other = user(org, Role.EXECUTIVE);
        String phone = uniquePhone();
        Lead lead = lead(org, owner.getId(), phone, null);
        storedMessage(org, null, "Lead", lead.getId(), phone);       // inbound, linked to owner's lead
        storedMessage(org, sender.getId(), null, null, phone);        // sent by FIELD_SALES user, unlinked

        String url = "/api/whatsapp/conversations/phone/" + phone;
        JsonNode asManager = body(getAs(manager, url).andExpect(status().isOk()));
        assertThat(asManager).hasSize(2);
        asManager.forEach(m -> assertThat(m.path("body").asText()).isEqualTo("secret deal terms"));

        JsonNode asOther = body(getAs(other, url).andExpect(status().isOk()));
        assertThat(asOther).hasSize(2);
        asOther.forEach(m -> {
            assertThat(m.path("body").isNull()).isTrue();
            assertThat(m.path("templateParams").isNull()).isTrue();
            assertThat(m.path("bodyHidden").asBoolean()).isTrue();
            assertThat(m.path("status").asText()).isEqualTo("delivered");   // metadata stays visible
        });

        JsonNode asOwner = body(getAs(owner, url));
        assertThat(asOwner.findValuesAsText("bodyHidden")).containsExactlyInAnyOrder("false", "true");
        JsonNode asSender = body(getAs(sender, url));
        assertThat(asSender.findValuesAsText("bodyHidden")).containsExactlyInAnyOrder("false", "true");

        // Inbox shows the thread with a masked preview for the outsider.
        JsonNode inbox = body(getAs(other, "/api/whatsapp/conversations").andExpect(status().isOk()));
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).path("phone").asText()).isEqualTo(phone);
        assertThat(inbox.get(0).path("messageCount").asInt()).isEqualTo(2);
        assertThat(inbox.get(0).path("lastMessage").path("bodyHidden").asBoolean()).isTrue();

        // Another org sees nothing of it.
        assertThat(body(getAs(user(newOrg(), Role.SUPER_ADMIN), url))).isEmpty();
        // Record thread requires access to the record itself.
        getAs(other, "/api/whatsapp/conversations/Lead/" + lead.getId()).andExpect(status().isForbidden());
        getAs(owner, "/api/whatsapp/conversations/Lead/" + lead.getId()).andExpect(status().isOk());
    }

    @Test
    void manualConsentNeedsReasonAndOptOutBlocksSending() throws Exception {
        Long org = newOrg();
        WhatsAppAccount acct = offlineAccount(org);
        User exec = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        String phone = uniquePhone();

        postAs(exec, "/api/whatsapp/consent", Map.of("phone", phone, "status", "opted_out"))
                .andExpect(status().isBadRequest());
        postAs(support, "/api/whatsapp/consent", Map.of("phone", phone, "status", "opted_out", "reason", "asked"))
                .andExpect(status().isForbidden());
        postAs(exec, "/api/whatsapp/consent", Map.of("phone", phone, "status", "opted_out", "reason", "Asked on a call"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("opted_out"))
                .andExpect(jsonPath("$.history[0].source").value("manual"))
                .andExpect(jsonPath("$.history[0].actorUserId").value(exec.getId()));

        postAs(exec, "/api/whatsapp/send", Map.of("accountId", acct.getId(), "phone", phone, "text", "hello"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("opted out")));

        postAs(exec, "/api/whatsapp/consent", Map.of("phone", phone, "status", "opted_in", "reason", "Re-subscribed by email"));
        JsonNode view = body(getAs(support, "/api/whatsapp/consent/" + phone).andExpect(status().isOk()));
        assertThat(view.path("status").asText()).isEqualTo("opted_in");
        assertThat(view.path("history")).hasSize(2);

        // Consent is per organization.
        JsonNode foreign = body(getAs(user(newOrg(), Role.SUPER_ADMIN), "/api/whatsapp/consent/" + phone));
        assertThat(foreign.path("status").asText()).isEqualTo("unknown");
    }
}
