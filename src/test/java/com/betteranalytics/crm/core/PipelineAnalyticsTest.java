package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deal funnel (Pipedrive attribution), win/loss and velocity reports. */
class PipelineAnalyticsTest extends CrmTestSupport {

    private static final long DAY = 86_400L;

    @Autowired JdbcTemplate jdbc;

    private void move(User u, long id, String stage) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("stage", stage);
        if (stage.equals("Won")) body.put("closeReason", "Best fit");
        if (stage.equals("Lost")) body.put("closeReason", "Price");
        patchAs(u, "/api/opportunities/" + id, body).andExpect(status().isOk());
    }

    private static Map<String, JsonNode> byStage(JsonNode funnel) {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode s : funnel.path("stages")) out.put(s.path("stage").asText(), s);
        return out;
    }

    @Test
    void funnelCountsWonDealsThroughEveryStageAndLostDealsUpToWhereTheyWereLost() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User otherManager = user(org, Role.MANAGER);
        User otherRep = user(org, Role.EXECUTIVE, otherManager.getId());

        long w1 = opp(rep, m("value", 100));          // Qualification → Won directly: passes every stage
        move(rep, w1, "Won");
        long l1 = opp(rep, m("value", 100));          // reached Proposal, lost there
        move(rep, l1, "Proposal");
        move(rep, l1, "Lost");
        long l2 = opp(rep, m("value", 100));          // lost in Qualification
        move(rep, l2, "Lost");
        long o1 = opp(rep, m("value", 100));          // open, sitting in Negotiation
        move(rep, o1, "Negotiation");
        long foreign = opp(otherRep, m("value", 100)); // another team: never counted
        move(otherRep, foreign, "Won");

        JsonNode f = getJson(manager, "/api/reports/deal-funnel");
        Map<String, JsonNode> s = byStage(f);
        assertThat(s.get("Qualification").path("reached").asLong()).isEqualTo(3);
        assertThat(s.get("Proposal").path("reached").asLong()).isEqualTo(2);
        assertThat(s.get("Negotiation").path("reached").asLong()).isEqualTo(1);
        assertThat(s.get("Verbal Commit").path("reached").asLong()).isEqualTo(1);
        assertThat(s.get("Won").path("reached").asLong()).isEqualTo(1);
        assertThat(s.get("Proposal").path("conversionFromPrevious").asDouble()).isCloseTo(66.7, within(0.05));
        assertThat(s.get("Qualification").path("conversionFromStart").asDouble()).isEqualTo(100.0);
        assertThat(f.path("won").asLong()).isEqualTo(1);
        assertThat(f.path("lost").asLong()).isEqualTo(2);
        assertThat(f.path("winRate").asDouble()).isCloseTo(33.3, within(0.05));
        assertThat(f.path("includeOpen").asBoolean()).isFalse();

        JsonNode withOpen = getJson(manager, "/api/reports/deal-funnel?includeOpen=true");
        Map<String, JsonNode> so = byStage(withOpen);
        assertThat(so.get("Qualification").path("reached").asLong()).isEqualTo(4);
        assertThat(so.get("Proposal").path("reached").asLong()).isEqualTo(3);
        assertThat(so.get("Negotiation").path("reached").asLong()).isEqualTo(2);
        assertThat(so.get("Verbal Commit").path("reached").asLong()).isEqualTo(1);
        assertThat(withOpen.path("open").asLong()).isEqualTo(1);

        // the other manager only sees their own team's single won deal
        JsonNode other = getJson(otherManager, "/api/reports/deal-funnel");
        assertThat(byStage(other).get("Qualification").path("reached").asLong()).isEqualTo(1);
        assertThat(byStage(other).get("Won").path("reached").asLong()).isEqualTo(1);
    }

    @Test
    void winLossByOwnerAndByCloseReason() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User rep2 = user(org, Role.EXECUTIVE, manager.getId());
        User inside = user(org, Role.INSIDE_SALES, manager.getId());
        long a = opp(rep, m("value", 1000));
        move(rep, a, "Won");
        long b = opp(rep, m("value", 400));
        move(rep, b, "Lost");
        long c = opp(rep2, m("value", 300));
        move(rep2, c, "Lost");
        opp(rep2, m("value", 999)); // open: not in win/loss

        JsonNode wl = getJson(manager, "/api/reports/win-loss");
        Map<Long, JsonNode> owners = new HashMap<>();
        for (JsonNode o : wl.path("byOwner")) owners.put(o.path("ownerId").asLong(), o);
        assertThat(owners.get(rep.getId()).path("won").asLong()).isEqualTo(1);
        assertThat(owners.get(rep.getId()).path("lost").asLong()).isEqualTo(1);
        assertThat(owners.get(rep.getId()).path("wonValue").asLong()).isEqualTo(1000);
        assertThat(owners.get(rep.getId()).path("winRate").asDouble()).isEqualTo(50.0);
        assertThat(owners.get(rep.getId()).path("name").asText()).isEqualTo(rep.getName());
        assertThat(owners.get(rep2.getId()).path("lost").asLong()).isEqualTo(1);
        assertThat(owners.get(rep2.getId()).path("winRate").asDouble()).isEqualTo(0.0);

        Map<String, JsonNode> reasons = new HashMap<>();
        for (JsonNode r : wl.path("byReason")) reasons.put(r.path("outcome").asText() + ":" + r.path("reason").asText(), r);
        assertThat(reasons.get("Lost:Price").path("count").asLong()).isEqualTo(2);
        assertThat(reasons.get("Lost:Price").path("value").asLong()).isEqualTo(700);
        assertThat(reasons.get("Won:Best fit").path("count").asLong()).isEqualTo(1);
        assertThat(wl.path("totals").path("won").asLong()).isEqualTo(1);
        assertThat(wl.path("totals").path("lost").asLong()).isEqualTo(2);

        // Inside Sales: counts only (no deals in their scope here, but values must never appear)
        jdbc.update("UPDATE opportunities SET sourced_by_user_id = ? WHERE id = ?", inside.getId(), a);
        JsonNode masked = getJson(inside, "/api/reports/win-loss");
        assertThat(masked.path("byOwner").get(0).path("won").asLong()).isEqualTo(1);
        assertThat(masked.path("byOwner").get(0).path("wonValue").isNull()).isTrue();
        assertThat(masked.path("byReason").get(0).path("value").isNull()).isTrue();
    }

    @Test
    void leadFunnelOnlyShowsStagesInUse() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        lead(rep, m("stage", "Contacted"));
        lead(rep, m("stage", "Contacted"));
        lead(rep);
        JsonNode f = getJson(rep, "/api/reports/funnel");
        assertThat(f.findValuesAsText("stage")).containsExactly("New", "Contacted", "Won");
        assertThat(f.get(1).path("count").asLong()).isEqualTo(2);
    }

    @Test
    void velocityAveragesDaysPerStageAndDaysToCloseWon() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long d1 = opp(rep, m("value", 100));
        move(rep, d1, "Proposal");
        move(rep, d1, "Won");
        long d2 = opp(rep, m("value", 100));
        move(rep, d2, "Proposal");

        // pretend the stints took 5 and 3 days in Qualification, 10 days in Proposal; d1 closed after 15 days
        jdbc.update("UPDATE opportunity_stage_history SET seconds_in_from_stage = ? WHERE opportunity_id = ? AND from_stage = 'Qualification'", 5 * DAY, d1);
        jdbc.update("UPDATE opportunity_stage_history SET seconds_in_from_stage = ? WHERE opportunity_id = ? AND from_stage = 'Qualification'", 3 * DAY, d2);
        jdbc.update("UPDATE opportunity_stage_history SET seconds_in_from_stage = ?, seconds_since_created = ? WHERE opportunity_id = ? AND to_stage = 'Won'", 10 * DAY, 15 * DAY, d1);

        JsonNode v = getJson(rep, "/api/reports/velocity");
        Map<String, JsonNode> s = new HashMap<>();
        for (JsonNode r : v.path("stages")) s.put(r.path("stage").asText(), r);
        assertThat(s.get("Qualification").path("avgDays").asDouble()).isCloseTo(4.0, within(0.01));
        assertThat(s.get("Qualification").path("samples").asLong()).isEqualTo(2);
        assertThat(s.get("Proposal").path("avgDays").asDouble()).isCloseTo(10.0, within(0.01));
        assertThat(s.get("Negotiation").path("avgDays").isNull()).isTrue();
        assertThat(v.path("avgDaysToCloseWon").asDouble()).isCloseTo(15.0, within(0.01));
        assertThat(v.path("wonSamples").asLong()).isEqualTo(1);

        // scope: another org's user sees nothing
        User stranger = user(newOrg(), Role.EXECUTIVE);
        JsonNode none = getJson(stranger, "/api/reports/velocity");
        assertThat(none.path("avgDaysToCloseWon").isNull()).isTrue();
    }
}
