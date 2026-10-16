package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.OpportunityStageHistory;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.OpportunityStageHistoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opportunity stage history (append-only) and forecast categories synced from the stage mapping. */
class StageHistoryAndForecastCategoryTest extends CrmTestSupport {

    @Autowired OpportunityStageHistoryRepository historyRepository;
    @Autowired JdbcTemplate jdbc;

    private List<OpportunityStageHistory> history(User u, long oppId) {
        return historyRepository.findByOpportunityIdAndOrganizationIdOrderByEnteredAtAscIdAsc(oppId, u.getOrganizationId());
    }

    // ── stage history ───────────────────────────────────────────────────────────

    @Test
    void createWritesInitialRowAndEveryStageChangeAppends() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long id = opp(rep, m("value", 1000));

        List<OpportunityStageHistory> rows = history(rep, id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getFromStage()).isNull();
        assertThat(rows.get(0).getToStage()).isEqualTo("Qualification");
        assertThat(rows.get(0).getChangedBy()).isEqualTo(rep.getId());
        assertThat(rows.get(0).getOrganizationId()).isEqualTo(org);

        patchAs(rep, "/api/opportunities/" + id, m("stage", "Proposal")).andExpect(status().isOk());
        patchAs(rep, "/api/opportunities/" + id, m("name", "Renamed")).andExpect(status().isOk()); // no stage change
        patchAs(manager, "/api/opportunities/" + id, m("stage", "Won", "closeReason", "Best price")).andExpect(status().isOk());

        rows = history(rep, id);
        assertThat(rows).extracting(OpportunityStageHistory::getToStage).containsExactly("Qualification", "Proposal", "Won");
        assertThat(rows.get(1).getFromStage()).isEqualTo("Qualification");
        assertThat(rows.get(1).getSecondsInFromStage()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(rows.get(2).getFromStage()).isEqualTo("Proposal");
        assertThat(rows.get(2).getChangedBy()).isEqualTo(manager.getId());
        assertThat(rows.get(2).getSecondsSinceCreated()).isNotNull();

        // readable through the API, scoped like the deal itself
        JsonNode api = getJson(rep, "/api/opportunities/" + id + "/stage-history");
        assertThat(api).hasSize(3);
        assertThat(api.get(2).path("toStage").asText()).isEqualTo("Won");
        User otherRep = user(org, Role.EXECUTIVE, manager.getId());
        getAs(otherRep, "/api/opportunities/" + id + "/stage-history").andExpect(status().isForbidden());
    }

    @Test
    void createdInALaterStageStartsThereAndLeadConversionWritesHistory() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long id = opp(rep, m("stage", "Negotiation"));
        assertThat(history(rep, id)).extracting(OpportunityStageHistory::getToStage).containsExactly("Negotiation");

        long leadId = lead(rep, m("email", "pat" + System.nanoTime() + "@example.com"));
        JsonNode conv = body(postAs(rep, "/api/leads/" + leadId + "/convert", Map.of()).andExpect(status().isOk()));
        long oppId = conv.path("opportunity").path("id").asLong();
        assertThat(history(rep, oppId)).extracting(OpportunityStageHistory::getToStage).containsExactly("Qualification");
        assertThat(conv.path("opportunity").path("forecastCategory").asText()).isEqualTo("PIPELINE");
    }

    // ── forecast categories ─────────────────────────────────────────────────────

    @Test
    void categoryFollowsTheDefaultStageMapping() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long id = opp(rep, m("value", 1000));
        assertThat(getJson(rep, "/api/opportunities/" + id).path("forecastCategory").asText()).isEqualTo("PIPELINE");

        Map<String, String> expected = Map.of(
                "Proposal", "PIPELINE", "Negotiation", "BEST_CASE", "Verbal Commit", "COMMIT");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            JsonNode res = body(patchAs(rep, "/api/opportunities/" + id, m("stage", e.getKey())).andExpect(status().isOk()));
            assertThat(res.path("forecastCategory").asText()).as(e.getKey()).isEqualTo(e.getValue());
        }
        JsonNode won = body(patchAs(rep, "/api/opportunities/" + id, m("stage", "Won", "closeReason", "Signed")).andExpect(status().isOk()));
        assertThat(won.path("forecastCategory").asText()).isEqualTo("CLOSED_WON");

        long lost = opp(rep, m("stage", "Lost", "closeReason", "Budget cut"));
        assertThat(getJson(rep, "/api/opportunities/" + lost).path("forecastCategory").asText()).isEqualTo("NOT_FORECASTED");

        JsonNode settings = getJson(rep, "/api/pipeline-settings");
        assertThat(settings.path("stageForecastCategories").path("Negotiation").asText()).isEqualTo("BEST_CASE");
        assertThat(settings.path("forecastCategories")).hasSize(5);
    }

    @Test
    void repOverrideSurvivesStageChangesUntilCleared() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long id = opp(rep, m("stage", "Proposal", "value", 500));

        JsonNode res = body(patchAs(rep, "/api/opportunities/" + id, m("forecastCategory", "COMMIT")).andExpect(status().isOk()));
        assertThat(res.path("forecastCategory").asText()).isEqualTo("COMMIT");
        assertThat(res.path("forecastCategoryOverridden").asBoolean()).isTrue();

        res = body(patchAs(rep, "/api/opportunities/" + id, m("stage", "Negotiation")).andExpect(status().isOk()));
        assertThat(res.path("forecastCategory").asText()).isEqualTo("COMMIT");

        res = body(patchAs(rep, "/api/opportunities/" + id, m("forecastCategoryOverridden", false)).andExpect(status().isOk()));
        assertThat(res.path("forecastCategory").asText()).isEqualTo("BEST_CASE");
        assertThat(res.path("forecastCategoryOverridden").asBoolean()).isFalse();

        // Closed won is reserved for won deals; unknown values are rejected
        patchAs(rep, "/api/opportunities/" + id, m("forecastCategory", "CLOSED_WON")).andExpect(status().isBadRequest());
        patchAs(rep, "/api/opportunities/" + id, m("forecastCategory", "MAYBE")).andExpect(status().isBadRequest());

        // closing clears the override and applies the closed stage's category
        patchAs(rep, "/api/opportunities/" + id, m("forecastCategory", "NOT_FORECASTED")).andExpect(status().isOk());
        res = body(patchAs(rep, "/api/opportunities/" + id, m("stage", "Lost", "closeReason", "Went quiet")).andExpect(status().isOk()));
        assertThat(res.path("forecastCategory").asText()).isEqualTo("NOT_FORECASTED");
        assertThat(res.path("forecastCategoryOverridden").asBoolean()).isFalse();
        // an override on a closed deal is refused
        patchAs(rep, "/api/opportunities/" + id, m("forecastCategory", "COMMIT")).andExpect(status().isBadRequest());

        // set on create
        long created = opp(rep, m("stage", "Qualification", "forecastCategory", "BEST_CASE"));
        JsonNode c = getJson(rep, "/api/opportunities/" + created);
        assertThat(c.path("forecastCategory").asText()).isEqualTo("BEST_CASE");
        assertThat(c.path("forecastCategoryOverridden").asBoolean()).isTrue();
    }

    @Test
    void managersCanRemapStagesToCategories() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());

        putAs(rep, "/api/pipeline-settings", m("stageForecastCategories", Map.of("Proposal", "BEST_CASE")))
                .andExpect(status().isForbidden());
        putAs(manager, "/api/pipeline-settings", m("stageForecastCategories", Map.of("Won", "COMMIT")))
                .andExpect(status().isBadRequest());
        putAs(manager, "/api/pipeline-settings", m("stageForecastCategories", Map.of("Proposal", "CLOSED_WON")))
                .andExpect(status().isBadRequest());
        putAs(manager, "/api/pipeline-settings", m("stageForecastCategories", Map.of("Nope", "COMMIT")))
                .andExpect(status().isBadRequest());

        JsonNode saved = body(putAs(manager, "/api/pipeline-settings", m("stageForecastCategories", Map.of("Proposal", "BEST_CASE")))
                .andExpect(status().isOk()));
        assertThat(saved.path("stageForecastCategories").path("Proposal").asText()).isEqualTo("BEST_CASE");
        // probabilities untouched by a category-only update
        assertThat(saved.path("stageProbabilities").path("Proposal").asInt()).isEqualTo(30);

        long id = opp(rep, m("stage", "Proposal"));
        assertThat(getJson(rep, "/api/opportunities/" + id).path("forecastCategory").asText()).isEqualTo("BEST_CASE");
    }

    @Test
    void weightedValueIsValueTimesProbability() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long id = opp(rep, m("stage", "Proposal", "value", 1000));
        JsonNode o = getJson(rep, "/api/opportunities/" + id);
        assertThat(o.path("probability").asInt()).isEqualTo(30);
        assertThat(o.path("weightedValue").asLong()).isEqualTo(300);
    }

    @Test
    void nightlySafetyNetBackfillsDealsWithoutHistory() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long id = opp(rep, m("stage", "Proposal"));
        jdbc.update("DELETE FROM opportunity_stage_history WHERE opportunity_id = ?", id);
        jdbc.update("UPDATE opportunities SET forecast_category = NULL WHERE id = ?", id);
        assertThat(history(rep, id)).isEmpty();

        healthJob.recomputeAll(java.time.Instant.now());

        assertThat(history(rep, id)).extracting(OpportunityStageHistory::getToStage).containsExactly("Proposal");
        assertThat(getJson(rep, "/api/opportunities/" + id).path("forecastCategory").asText()).isEqualTo("PIPELINE");
    }

    @Autowired com.betteranalytics.crm.pipeline.DealHealthJob healthJob;
}
