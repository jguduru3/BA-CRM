package com.betteranalytics.crm.engagement;

import com.betteranalytics.crm.entity.CampaignContact;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.WhatsAppAccount;
import com.betteranalytics.crm.repository.CampaignContactRepository;
import com.betteranalytics.crm.repository.CampaignRepository;
import com.betteranalytics.crm.service.WhatsAppConsentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Campaign status flow, approval role rules, optimistic locking, recipients, background sending. */
class CampaignFlowTest extends EngagementTestSupport {

    @Autowired CampaignRepository campaignRepository;
    @Autowired CampaignContactRepository contactRepository;
    @Autowired WhatsAppConsentService consentService;
    @Autowired JdbcTemplate jdbc;

    private long createCampaign(User u, WhatsAppAccount acct) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Diwali offer");
        body.put("type", "whatsapp");
        body.put("templateName", "diwali_offer");
        body.put("templateLanguage", "en_US");
        body.put("templateParams", "[{\"type\":\"body\",\"parameters\":[{\"type\":\"text\",\"text\":\"20%\"}]}]");
        if (acct != null) body.put("waAccountId", acct.getId());
        return idOf(postAs(u, "/api/campaigns", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft")));
    }

    private String campaignStatus(long id) {
        return campaignRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void permissionsAndTenantIsolation() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        long id = createCampaign(vp, null);

        postAs(user(org, Role.EXECUTIVE), "/api/campaigns", Map.of("name", "x")).andExpect(status().isForbidden());
        postAs(user(org, Role.SUPPORT), "/api/campaigns", Map.of("name", "x")).andExpect(status().isForbidden());
        getAs(user(org, Role.EXECUTIVE), "/api/campaigns/" + id).andExpect(status().isOk());
        putAs(user(org, Role.MANAGER), "/api/campaigns/" + id, Map.of("name", "y")).andExpect(status().isForbidden());

        User foreign = user(newOrg(), Role.SUPER_ADMIN);
        getAs(foreign, "/api/campaigns/" + id).andExpect(status().isNotFound());
        postAs(foreign, "/api/campaigns/" + id + "/submit", Map.of()).andExpect(status().isNotFound());
        postAs(foreign, "/api/campaigns/" + id + "/contacts", List.of(Map.of("phone", uniquePhone())))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvalFlowWithRoleRulesAndRejectReason() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        User otherVp = user(org, Role.VP_SALES);
        User admin = user(org, Role.SUPER_ADMIN);
        User manager = user(org, Role.MANAGER);
        long id = createCampaign(vp, null);

        postAs(vp, "/api/campaigns/" + id + "/pause", Map.of()).andExpect(status().isConflict());     // illegal transition
        postAs(otherVp, "/api/campaigns/" + id + "/approve", Map.of()).andExpect(status().isConflict());   // draft cannot be approved
        postAs(vp, "/api/campaigns/" + id + "/launch", Map.of()).andExpect(status().isForbidden());   // draft launch: SUPER_ADMIN only

        postAs(vp, "/api/campaigns/" + id + "/submit", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("submitted"));
        postAs(vp, "/api/campaigns/" + id + "/approve", Map.of()).andExpect(status().isForbidden());  // own campaign
        postAs(vp, "/api/campaigns/" + id + "/reject", Map.of("reason", "x")).andExpect(status().isForbidden());
        postAs(manager, "/api/campaigns/" + id + "/approve", Map.of()).andExpect(status().isForbidden());

        postAs(otherVp, "/api/campaigns/" + id + "/reject", Map.of()).andExpect(status().isBadRequest());
        postAs(otherVp, "/api/campaigns/" + id + "/reject", Map.of("reason", "  ")).andExpect(status().isBadRequest());
        postAs(otherVp, "/api/campaigns/" + id + "/reject", Map.of("reason", "Template not approved yet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.rejectionReason").value("Template not approved yet"));

        // Editing a rejected campaign puts it back to draft; then resubmit + approve.
        putAs(vp, "/api/campaigns/" + id, Map.of("templateName", "diwali_offer_v2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("draft"));
        postAs(vp, "/api/campaigns/" + id + "/submit", Map.of()).andExpect(status().isOk());
        postAs(otherVp, "/api/campaigns/" + id + "/approve", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.approvedBy").value(otherVp.getId()));
        putAs(vp, "/api/campaigns/" + id, Map.of("name", "late edit")).andExpect(status().isConflict());

        // SUPER_ADMIN may approve their own campaign.
        long own = createCampaign(admin, null);
        postAs(admin, "/api/campaigns/" + own + "/submit", Map.of()).andExpect(status().isOk());
        postAs(admin, "/api/campaigns/" + own + "/approve", Map.of()).andExpect(status().isOk());
        postAs(admin, "/api/campaigns/" + own + "/cancel", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        postAs(admin, "/api/campaigns/" + own + "/resume", Map.of()).andExpect(status().isConflict());
    }

    @Test
    void staleVersionIsRejectedWith409() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        long id = createCampaign(vp, null);
        long v0 = body(getAs(vp, "/api/campaigns/" + id)).path("campaign").path("version").asLong();

        putAs(vp, "/api/campaigns/" + id, Map.of("name", "first", "version", v0)).andExpect(status().isOk());
        putAs(vp, "/api/campaigns/" + id, Map.of("name", "second", "version", v0)).andExpect(status().isConflict());
        postAs(vp, "/api/campaigns/" + id + "/submit", Map.of("version", v0)).andExpect(status().isConflict());
        assertThat(campaignRepository.findById(id).orElseThrow().getName()).isEqualTo("first");
    }

    @Test
    void recipientsAreNormalizedDeduplicatedAndCounted() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        long id = createCampaign(vp, null);
        String p1 = uniquePhone();
        String p2 = uniquePhone();
        consentService.record(org, p2, "opted_out", "manual", "test", vp.getId(), null);

        JsonNode r = body(postAs(vp, "/api/campaigns/" + id + "/contacts", List.of(
                Map.of("phone", p1), Map.of("phoneNumber", p1.substring(0, 5) + " " + p1.substring(5)),
                Map.of("phone", p2), Map.of("phone", "12"), Map.of("name", "no phone")))
                .andExpect(status().isOk()));
        assertThat(r.path("added").asInt()).isEqualTo(2);
        assertThat(r.path("duplicates").asInt()).isEqualTo(1);
        assertThat(r.path("invalid").asInt()).isEqualTo(2);
        assertThat(r.path("optedOut").asInt()).isEqualTo(1);
        assertThat(r.path("total").asInt()).isEqualTo(2);

        JsonNode again = body(postAs(vp, "/api/campaigns/" + id + "/contacts", List.of(Map.of("phone", p1.substring(1))))
                .andExpect(status().isOk()));
        assertThat(again.path("added").asInt()).isZero();
        assertThat(again.path("duplicates").asInt()).isEqualTo(1);
        assertThat(contactRepository.findByCampaignId(id)).extracting(CampaignContact::getNormalizedPhone)
                .containsExactlyInAnyOrder(p1, p2);
    }

    @Test
    void launchSendsInBackgroundSkipsOptedOutAndCompletes() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        WhatsAppAccount acct = offlineAccount(org);
        long id = createCampaign(admin, acct);
        String ok1 = uniquePhone(), ok2 = uniquePhone(), out = uniquePhone();
        postAs(admin, "/api/campaigns/" + id + "/contacts",
                List.of(Map.of("phone", ok1), Map.of("phone", ok2), Map.of("phone", out))).andExpect(status().isOk());
        consentService.record(org, out, "opted_out", "manual", "test", admin.getId(), null);

        // SUPER_ADMIN may launch straight from draft.
        postAs(admin, "/api/campaigns/" + id + "/launch", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sending"));

        await("campaign completed", () -> "completed".equals(campaignStatus(id)));
        Map<String, String> byPhone = new HashMap<>();
        contactRepository.findByCampaignId(id).forEach(cc -> byPhone.put(cc.getNormalizedPhone(), cc.getStatus()));
        // The offline account has no token, so real sends fail — but each recipient got exactly one attempt.
        assertThat(byPhone).containsEntry(ok1, "failed").containsEntry(ok2, "failed")
                .containsEntry(out, "skipped_opted_out");
        Integer attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM whatsapp_messages WHERE campaign_id = ?", Integer.class, id);
        assertThat(attempts).isEqualTo(2);

        JsonNode progress = body(getAs(admin, "/api/campaigns/" + id + "/progress").andExpect(status().isOk()));
        assertThat(progress.path("status").asText()).isEqualTo("completed");
        assertThat(progress.path("percent").asDouble()).isEqualTo(100.0);
        assertThat(progress.path("counts").path("skipped_opted_out").asInt()).isEqualTo(1);
        assertThat(progress.path("failedCount").asInt()).isEqualTo(2);
        postAs(admin, "/api/campaigns/" + id + "/pause", Map.of()).andExpect(status().isConflict());
    }

    @Test
    void audiencePreviewAndApplyRunInTheDatabase() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        for (int i = 0; i < 3; i++) lead(org, vp.getId(), uniquePhone(), "Technology");
        lead(org, vp.getId(), null, "Technology");               // matches, but has no phone
        lead(org, vp.getId(), uniquePhone(), "Retail");
        lead(newOrg(), null, uniquePhone(), "Technology");      // another org
        long id = createCampaign(vp, null);
        String criteria = "{\"entity\":\"lead\",\"filters\":[{\"field\":\"industry\",\"op\":\"eq\",\"value\":\"technology\"}]}";

        JsonNode preview = body(postAs(vp, "/api/campaigns/" + id + "/audience/preview", Map.of("criteriaJson", criteria))
                .andExpect(status().isOk()));
        assertThat(preview.path("count").asInt()).isEqualTo(3);
        assertThat(preview.path("matching").asInt()).isEqualTo(4);
        assertThat(preview.path("withoutPhone").asInt()).isEqualTo(1);
        assertThat(preview.path("recipients")).hasSize(3);
        assertThat(preview.path("recipients").get(0).path("validPhone").asBoolean()).isTrue();

        postAs(vp, "/api/campaigns/" + id + "/audience/preview",
                Map.of("criteriaJson", "{\"filters\":[{\"field\":\"password\",\"op\":\"eq\",\"value\":1}]}"))
                .andExpect(status().isBadRequest());
        postAs(user(org, Role.EXECUTIVE), "/api/campaigns/" + id + "/audience/preview", Map.of())
                .andExpect(status().isForbidden());

        JsonNode applied = body(postAs(vp, "/api/campaigns/" + id + "/audience/apply", Map.of("criteriaJson", criteria))
                .andExpect(status().isOk()));
        assertThat(applied.path("added").asInt()).isEqualTo(3);
        JsonNode reapplied = body(postAs(vp, "/api/campaigns/" + id + "/audience/apply", Map.of()).andExpect(status().isOk()));
        assertThat(reapplied.path("added").asInt()).isZero();      // saved criteria reused, no double recipients
        assertThat(reapplied.path("duplicates").asInt()).isEqualTo(3);
        assertThat(contactRepository.countByCampaignId(id)).isEqualTo(3);
    }
}
