package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Database-side report aggregates respect record scope; the timeline pairs corrections with originals. */
class ReportsAndTimelineTest extends CrmTestSupport {

    @Test
    void reportAggregatesFollowRecordScope() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        User manager = user(org, Role.MANAGER, vp.getId());
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User otherRep = user(org, Role.EXECUTIVE);
        lead(rep, m("stage", "New", "source", "Website"));
        lead(rep, m("stage", "Won", "source", "Website"));
        lead(otherRep, m("stage", "New", "source", "Referral"));
        opp(rep, m("value", 100));
        opp(otherRep, m("value", 1000));
        long wonId = opp(rep, m("value", 50));
        patchAs(rep, "/api/opportunities/" + wonId, m("stage", "Won", "closeReason", "fit")).andExpect(status().isOk());
        task(rep, m("dueDate", "2001-01-01"));

        JsonNode mine = getJson(manager, "/api/reports/dashboard");
        assertThat(mine.path("totalLeads").asLong()).isEqualTo(2);
        assertThat(mine.path("activeLeads").asLong()).isEqualTo(1);
        assertThat(mine.path("totalOppValue").asLong()).isEqualTo(100);
        assertThat(mine.path("wonValue").asLong()).isEqualTo(50);
        assertThat(mine.path("wonOpps").asLong()).isEqualTo(1);
        assertThat(mine.path("overdueTasks").asLong()).isEqualTo(1);
        assertThat(mine.path("scope").asText()).isEqualTo("team");

        JsonNode all = getJson(vp, "/api/reports/dashboard");
        assertThat(all.path("totalLeads").asLong()).isEqualTo(3);
        assertThat(all.path("totalOppValue").asLong()).isEqualTo(1100);

        JsonNode sources = getJson(manager, "/api/reports/lead-sources");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).path("source").asText()).isEqualTo("Website");
        assertThat(sources.get(0).path("count").asLong()).isEqualTo(2);

        JsonNode pipeline = getJson(vp, "/api/reports/pipeline");
        assertThat(pipeline.get(0).path("stage").asText()).isEqualTo("Qualification");
        assertThat(pipeline.get(0).path("count").asLong()).isEqualTo(2);
        assertThat(pipeline.get(0).path("value").asLong()).isEqualTo(1100);

        JsonNode team = getJson(manager, "/api/reports/team");
        assertThat(team).hasSize(2); // the manager and their report
        for (JsonNode row : team) {
            if (row.path("id").asLong() == rep.getId()) {
                assertThat(row.path("leadsOwned").asLong()).isEqualTo(2);
                assertThat(row.path("pipelineValue").asLong()).isEqualTo(100);
                assertThat(row.path("wonValue").asLong()).isEqualTo(50);
            }
        }
        // Support is read-only but org-wide, so it sees the same totals as the VP
        User support = user(org, Role.SUPPORT);
        assertThat(getJson(support, "/api/reports/dashboard").path("totalLeads").asLong())
                .isEqualTo(getJson(vp, "/api/reports/dashboard").path("totalLeads").asLong());
        // a role with no feature access at all still gets zeros rather than an error
        User inside = user(newOrg(), Role.INSIDE_SALES);
        assertThat(getJson(inside, "/api/reports/dashboard").path("totalLeads").asLong()).isZero();
    }

    @Test
    void timelineReturnsCorrectionsAlongsideOriginals() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        long leadId = lead(manager);
        long original = activity(manager, m("relatedType", "Lead", "relatedId", leadId, "subject", "Called", "body", "Spoke to CEO"));
        JsonNode correction = body(postAs(manager, "/api/activities/" + original + "/correct",
                Map.of("subject", "Called", "body", "Spoke to CFO")).andExpect(status().isCreated()));
        // correcting a correction is refused: always correct the original
        postAs(manager, "/api/activities/" + correction.path("id").asLong() + "/correct", Map.of("body", "x"))
                .andExpect(status().isBadRequest());
        // still append-only: the original is untouched
        assertThat(getJson(manager, "/api/activities/" + original).path("body").asText()).isEqualTo("Spoke to CEO");

        JsonNode page = getJson(manager, "/api/activities?relatedType=Lead&relatedId=" + leadId + "&withCorrections=true");
        assertThat(page.path("count").asLong()).isEqualTo(2);
        assertThat(page.path("corrections").path(String.valueOf(original)).get(0).path("body").asText()).isEqualTo("Spoke to CFO");
        assertThat(page.path("originals").path(String.valueOf(original)).path("body").asText()).isEqualTo("Spoke to CEO");
        assertThat(getJson(manager, "/api/activities/" + original + "/corrections")).hasSize(1);
    }
}
