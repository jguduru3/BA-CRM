package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Company deletes with children; pipeline settings, stage probabilities, Won/Lost reasons, big-deal alerts. */
class CompanyDeleteAndPipelineTest extends CrmTestSupport {

    // ── company delete with children ────────────────────────────────────────────

    @Test
    void deletingACompanyWithChildrenIs409WithCountsUnlessCascade() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long companyId = company(manager, Map.of());
        long c1 = contact(rep, m("companyId", companyId));
        contact(rep, m("companyId", companyId));
        long opp = opp(rep, m("companyId", companyId));
        long lead = lead(rep, m("companyId", companyId));

        deleteAs(manager, "/api/companies/" + companyId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HAS_CHILDREN"))
                .andExpect(jsonPath("$.children.contacts").value(2))
                .andExpect(jsonPath("$.children.opportunities").value(1))
                .andExpect(jsonPath("$.children.leads").value(1))
                .andExpect(jsonPath("$.error").value(containsString("2 contacts, 1 opportunity, 1 lead")));
        getAs(manager, "/api/companies/" + companyId).andExpect(status().isOk());

        // a soft-deleted child no longer counts
        deleteAs(manager, "/api/contacts/" + c1).andExpect(status().isOk());
        deleteAs(manager, "/api/companies/" + companyId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.children.contacts").value(1));

        JsonNode res = body(deleteAs(manager, "/api/companies/" + companyId + "?cascade=true&reason=closed").andExpect(status().isOk()));
        assertThat(res.path("cascaded").path("contacts").asInt()).isEqualTo(1);
        getAs(manager, "/api/companies/" + companyId).andExpect(status().isNotFound());
        getAs(manager, "/api/opportunities/" + opp).andExpect(status().isNotFound());
        getAs(manager, "/api/leads/" + lead).andExpect(status().isNotFound());

        // restoring the company brings back exactly the children the cascade removed
        postAs(manager, "/api/companies/" + companyId + "/restore", Map.of()).andExpect(status().isOk());
        getAs(manager, "/api/opportunities/" + opp).andExpect(status().isOk());
        getAs(manager, "/api/leads/" + lead).andExpect(status().isOk());
        getAs(manager, "/api/contacts/" + c1).andExpect(status().isNotFound()); // deleted separately, stays deleted
    }

    @Test
    void emptyCompanyDeletesNormallyAndRepsCannotDelete() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long companyId = company(rep, Map.of());
        deleteAs(rep, "/api/companies/" + companyId + "?cascade=true").andExpect(status().isForbidden());
        deleteAs(manager, "/api/companies/" + companyId).andExpect(status().isOk());
    }

    // ── pipeline settings ───────────────────────────────────────────────────────

    @Test
    void stageDefaultsComeFromOrgSettingsUnlessOverridden() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());

        JsonNode defaults = getJson(rep, "/api/pipeline-settings");
        assertThat(defaults.path("stageProbabilities").path("Proposal").asInt()).isEqualTo(30);
        putAs(rep, "/api/pipeline-settings", m("stageProbabilities", Map.of("Proposal", 45))).andExpect(status().isForbidden());
        putAs(manager, "/api/pipeline-settings", m("stageProbabilities", Map.of("Proposal", 145))).andExpect(status().isBadRequest());
        putAs(manager, "/api/pipeline-settings", m("stageProbabilities", Map.of("Nope", 1))).andExpect(status().isBadRequest());
        putAs(manager, "/api/pipeline-settings", m("stageProbabilities", Map.of("Proposal", 45, "Negotiation", 65),
                "bigDealThreshold", 500_000)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stageProbabilities.Proposal").value(45))
                .andExpect(jsonPath("$.stageProbabilities.Qualification").value(10))
                .andExpect(jsonPath("$.bigDealThreshold").value(500_000));
        assertThat(audits(org, "update").stream().anyMatch(a -> "pipeline_settings".equals(a.getResource()))).isTrue();

        long id = opp(rep, m("stage", "Proposal", "probability", 50)); // the old form always sent 50
        assertThat(getJson(rep, "/api/opportunities/" + id).path("probability").asInt()).isEqualTo(45);
        patchAs(rep, "/api/opportunities/" + id, m("stage", "Negotiation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.probability").value(65));

        // a Manager's manual value sticks through stage changes...
        patchAs(manager, "/api/opportunities/" + id, m("probability", 90)).andExpect(status().isOk())
                .andExpect(jsonPath("$.probabilityOverridden").value(true));
        patchAs(rep, "/api/opportunities/" + id, m("stage", "Proposal")).andExpect(status().isOk())
                .andExpect(jsonPath("$.probability").value(90));
        // ...until the override is cleared
        patchAs(manager, "/api/opportunities/" + id, m("probabilityOverridden", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.probability").value(45));
        // reps can't set it by hand
        patchAs(rep, "/api/opportunities/" + id, m("probability", 99)).andExpect(status().isForbidden());
    }

    @Test
    void wonAndLostNeedAReason() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long id = opp(rep, Map.of());
        patchAs(rep, "/api/opportunities/" + id, m("stage", "Won")).andExpect(status().isBadRequest());
        patchAs(rep, "/api/opportunities/" + id, m("stage", "Lost", "closeReason", " ")).andExpect(status().isBadRequest());
        postAs(rep, "/api/opportunities", m("name", "x", "stage", "Lost")).andExpect(status().isBadRequest());

        JsonNode won = body(patchAs(rep, "/api/opportunities/" + id, m("stage", "Won", "closeReason", "Best price + support"))
                .andExpect(status().isOk()));
        assertThat(won.path("closeReason").asText()).isEqualTo("Best price + support");
        assertThat(won.path("closedAt").isNull()).isFalse();
        assertThat(won.path("probability").asInt()).isEqualTo(100);

        // re-opening clears the close details
        JsonNode reopened = body(patchAs(rep, "/api/opportunities/" + id, m("stage", "Negotiation")).andExpect(status().isOk()));
        assertThat(reopened.path("closeReason").isNull()).isTrue();
        assertThat(reopened.path("closedAt").isNull()).isTrue();
    }

    @Test
    void bigDealsAlertTheManagerChainAndVps() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        User admin = user(org, Role.SUPER_ADMIN);
        User manager = user(org, Role.MANAGER, vp.getId());
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User otherManager = user(org, Role.MANAGER, vp.getId());
        putAs(vp, "/api/pipeline-settings", m("bigDealThreshold", 1_000_000)).andExpect(status().isOk());

        opp(rep, m("value", 999_999)); // below threshold → silent
        assertThat(bigDealNotices(manager)).isZero();

        long big = opp(rep, m("value", 2_500_000));
        assertThat(bigDealNotices(manager)).isEqualTo(1);
        assertThat(bigDealNotices(vp)).isEqualTo(1);
        assertThat(bigDealNotices(admin)).isEqualTo(1);
        assertThat(bigDealNotices(otherManager)).isZero();
        assertThat(bigDealNotices(rep)).isZero();

        patchAs(rep, "/api/opportunities/" + big, m("stage", "Proposal")).andExpect(status().isOk());
        assertThat(bigDealNotices(manager)).isEqualTo(2);
        patchAs(rep, "/api/opportunities/" + big, m("notes", "no stage change")).andExpect(status().isOk());
        assertThat(bigDealNotices(manager)).isEqualTo(2);
    }

    private long bigDealNotices(User u) {
        return notificationsOf(u).stream().filter(n -> "big_deal".equals(n.getType())).count();
    }
}
