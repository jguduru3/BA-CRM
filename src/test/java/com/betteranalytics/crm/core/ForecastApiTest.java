package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.Team;
import com.betteranalytics.crm.entity.TeamMember;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.TeamMemberRepository;
import com.betteranalytics.crm.repository.TeamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/forecast: buckets by close date, totals per category, weighted sums, quota attainment, scope and masking. */
class ForecastApiTest extends CrmTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired TeamRepository teamRepository;
    @Autowired TeamMemberRepository teamMemberRepository;

    /** Rep's January 2027 book: one deal per category plus deals outside the bucket. */
    private void seedJanuary(User rep, User closer) throws Exception {
        opp(rep, m("stage", "Proposal", "value", 1000, "closeDate", "2027-01-10"));        // PIPELINE, p30 → 300
        opp(rep, m("stage", "Negotiation", "value", 2000, "closeDate", "2027-01-20"));     // BEST_CASE, p60 → 1200
        opp(rep, m("stage", "Verbal Commit", "value", 500, "closeDate", "2027-01-31"));    // COMMIT, p80 → 400
        long won = opp(rep, m("stage", "Proposal", "value", 700, "closeDate", "2027-01-05"));
        patchAs(closer, "/api/opportunities/" + won, m("stage", "Won", "closeReason", "Signed")).andExpect(status().isOk()); // CLOSED_WON 700
        opp(rep, m("stage", "Lost", "value", 900, "closeDate", "2027-01-15", "closeReason", "Price")); // NOT_FORECASTED, p0
        opp(rep, m("stage", "Proposal", "value", 4000, "closeDate", "2027-02-01"));        // next month
        opp(rep, m("stage", "Proposal", "value", 8000));                                  // no close date: never forecast
    }

    @Test
    void monthlyBucketSumsEachCategoryWithWeightedAmounts() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        seedJanuary(rep, rep);

        JsonNode f = getJson(rep, "/api/forecast?period=month&start=2027-01-01");
        assertThat(f.path("valuesVisible").asBoolean()).isTrue();
        assertThat(f.path("categories")).hasSize(5);
        JsonNode bucket = f.path("buckets").get(0);
        assertThat(bucket.path("start").asText()).isEqualTo("2027-01-01");
        assertThat(bucket.path("end").asText()).isEqualTo("2027-01-31");
        JsonNode t = bucket.path("totals");
        assertThat(t.path("PIPELINE").path("value").asLong()).isEqualTo(1000);
        assertThat(t.path("PIPELINE").path("weighted").asLong()).isEqualTo(300);
        assertThat(t.path("PIPELINE").path("count").asLong()).isEqualTo(1);
        assertThat(t.path("BEST_CASE").path("weighted").asLong()).isEqualTo(1200);
        assertThat(t.path("COMMIT").path("value").asLong()).isEqualTo(500);
        assertThat(t.path("COMMIT").path("weighted").asLong()).isEqualTo(400);
        assertThat(t.path("CLOSED_WON").path("value").asLong()).isEqualTo(700);
        assertThat(t.path("CLOSED_WON").path("weighted").asLong()).isEqualTo(700);
        assertThat(t.path("NOT_FORECASTED").path("count").asLong()).isEqualTo(1);
        assertThat(t.path("NOT_FORECASTED").path("weighted").asLong()).isEqualTo(0);
        assertThat(bucket.path("commitAndWon").asLong()).isEqualTo(1200);
        // cumulative roll-up: a category plus every higher one
        assertThat(bucket.path("cumulative").path("BEST_CASE").asLong()).isEqualTo(2000 + 500 + 700);
        assertThat(bucket.path("quota").isNull()).isTrue();
        assertThat(bucket.path("attainment").isNull()).isTrue();

        // quarter bucket also catches February
        JsonNode q = getJson(rep, "/api/forecast?period=quarter&start=2027-01-01");
        JsonNode qb = q.path("buckets").get(0);
        assertThat(qb.path("end").asText()).isEqualTo("2027-03-31");
        assertThat(qb.path("totals").path("PIPELINE").path("value").asLong()).isEqualTo(5000);

        // several buckets; start is snapped to the period start
        JsonNode multi = getJson(rep, "/api/forecast?period=month&start=2027-01-17&periods=2");
        assertThat(multi.path("buckets")).hasSize(2);
        assertThat(multi.path("buckets").get(0).path("start").asText()).isEqualTo("2027-01-01");
        assertThat(multi.path("buckets").get(1).path("totals").path("PIPELINE").path("value").asLong()).isEqualTo(4000);

        getAs(rep, "/api/forecast?period=week&start=2027-01-01").andExpect(status().isBadRequest());
        getAs(rep, "/api/forecast?period=month&start=2027-01-01&groupBy=planet").andExpect(status().isBadRequest());
    }

    @Test
    void quotaAttainmentIsCommitPlusClosedWonOverQuota() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User otherManager = user(org, Role.MANAGER);
        seedJanuary(rep, rep);

        String quota = "/api/forecast/quotas";
        putAs(rep, quota, m("userId", rep.getId(), "period", "month", "periodStart", "2027-01-01", "amount", 2400))
                .andExpect(status().isForbidden());
        putAs(otherManager, quota, m("userId", rep.getId(), "period", "month", "periodStart", "2027-01-01", "amount", 2400))
                .andExpect(status().isForbidden());
        putAs(manager, quota, m("userId", rep.getId(), "period", "month", "periodStart", "2027-01-15", "amount", 2400))
                .andExpect(status().isBadRequest()); // not a period start
        putAs(manager, quota, m("userId", rep.getId(), "period", "month", "periodStart", "2027-01-01", "amount", -5))
                .andExpect(status().isBadRequest());
        putAs(manager, quota, m("userId", rep.getId(), "period", "month", "periodStart", "2027-01-01", "amount", 1000))
                .andExpect(status().isOk());
        putAs(manager, quota, m("userId", rep.getId(), "period", "month", "periodStart", "2027-01-01", "amount", 2400))
                .andExpect(status().isOk()); // upsert

        JsonNode bucket = getJson(rep, "/api/forecast?period=month&start=2027-01-01").path("buckets").get(0);
        assertThat(bucket.path("quota").asLong()).isEqualTo(2400);
        assertThat(bucket.path("attainment").asDouble()).isEqualTo(50.0);

        JsonNode list = getJson(manager, quota + "?period=month&start=2027-01-01");
        assertThat(list.findValuesAsText("userId")).contains(String.valueOf(rep.getId()));
        assertThat(audits(org, "update").stream().anyMatch(a -> "forecast_quotas".equals(a.getResource()))).isTrue();

        // amount null removes the quota
        putAs(manager, quota, m("userId", rep.getId(), "period", "month", "periodStart", "2027-01-01", "amount", null))
                .andExpect(status().isOk());
        assertThat(getJson(rep, "/api/forecast?period=month&start=2027-01-01").path("buckets").get(0).path("quota").isNull()).isTrue();
    }

    @Test
    void forecastIsScopedToTheTeamAndGroupsByOwnerAndTeam() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        User managerA = user(org, Role.MANAGER, vp.getId());
        User managerB = user(org, Role.MANAGER, vp.getId());
        User repA = user(org, Role.EXECUTIVE, managerA.getId());
        User repB = user(org, Role.EXECUTIVE, managerB.getId());
        opp(repA, m("stage", "Verbal Commit", "value", 1000, "closeDate", "2027-01-10"));
        opp(repB, m("stage", "Verbal Commit", "value", 3000, "closeDate", "2027-01-10"));
        Team team = new Team();
        team.setOrganizationId(org);
        team.setName("North");
        team.setLeaderId(managerA.getId());
        team = teamRepository.save(team);
        TeamMember tm = new TeamMember();
        tm.setTeamId(team.getId());
        tm.setUserId(repA.getId());
        teamMemberRepository.save(tm);

        JsonNode a = getJson(managerA, "/api/forecast?period=month&start=2027-01-01&groupBy=owner").path("buckets").get(0);
        assertThat(a.path("totals").path("COMMIT").path("value").asLong()).isEqualTo(1000);
        // every seller in the manager's team is listed (quota targets), nobody from the other team
        assertThat(a.path("groups").findValuesAsText("name")).containsExactlyInAnyOrder(managerA.getName(), repA.getName());
        for (JsonNode g : a.path("groups")) {
            long expected = g.path("id").asLong() == repA.getId() ? 1000 : 0;
            assertThat(g.path("totals").path("COMMIT").path("value").asLong()).isEqualTo(expected);
        }

        JsonNode rep = getJson(repA, "/api/forecast?period=month&start=2027-01-01").path("buckets").get(0);
        assertThat(rep.path("totals").path("COMMIT").path("value").asLong()).isEqualTo(1000);

        JsonNode all = getJson(vp, "/api/forecast?period=month&start=2027-01-01&groupBy=team").path("buckets").get(0);
        assertThat(all.path("totals").path("COMMIT").path("value").asLong()).isEqualTo(4000);
        assertThat(all.path("groups").findValuesAsText("name")).containsExactlyInAnyOrder("North", "No team");
        for (JsonNode g : all.path("groups")) {
            long expected = g.path("name").asText().equals("North") ? 1000 : 3000;
            assertThat(g.path("totals").path("COMMIT").path("value").asLong()).isEqualTo(expected);
        }
    }

    @Test
    void insideSalesSeesCountsButNeverValues() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User inside = user(org, Role.INSIDE_SALES, manager.getId());
        long sourced = opp(rep, m("stage", "Verbal Commit", "value", 1000, "closeDate", "2027-01-10"));
        opp(rep, m("stage", "Verbal Commit", "value", 5000, "closeDate", "2027-01-10")); // not sourced by inside
        jdbc.update("UPDATE opportunities SET sourced_by_user_id = ? WHERE id = ?", inside.getId(), sourced);
        putAs(manager, "/api/forecast/quotas", m("userId", inside.getId(), "period", "month", "periodStart", "2027-01-01", "amount", 100))
                .andExpect(status().isOk());

        JsonNode f = getJson(inside, "/api/forecast?period=month&start=2027-01-01&groupBy=owner");
        assertThat(f.path("valuesVisible").asBoolean()).isFalse();
        JsonNode b = f.path("buckets").get(0);
        assertThat(b.path("totals").path("COMMIT").path("count").asLong()).isEqualTo(1);
        assertThat(b.path("totals").path("COMMIT").path("value").isNull()).isTrue();
        assertThat(b.path("totals").path("COMMIT").path("weighted").isNull()).isTrue();
        assertThat(b.path("commitAndWon").isNull()).isTrue();
        assertThat(b.path("quota").isNull()).isTrue();
        assertThat(b.path("attainment").isNull()).isTrue();
        assertThat(b.path("cumulative").path("COMMIT").isNull()).isTrue();
        assertThat(b.path("groups")).isNotEmpty();
        for (JsonNode g : b.path("groups")) {
            assertThat(g.path("totals").path("COMMIT").path("value").isNull()).isTrue();
            assertThat(g.path("quota").isNull()).isTrue();
        }
        // and the quota list hides amounts too
        JsonNode quotas = getJson(inside, "/api/forecast/quotas?period=month&start=2027-01-01");
        for (JsonNode q : quotas) assertThat(q.path("amount").isNull()).isTrue();
    }
}
