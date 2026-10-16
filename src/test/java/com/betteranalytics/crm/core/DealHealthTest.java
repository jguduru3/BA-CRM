package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.AppNotification;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.pipeline.DealHealthJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rules-first deal health score, its explaining factors, and risk-flag notifications (once per flag change). */
class DealHealthTest extends CrmTestSupport {

    private static final long DAY = 86_400L;

    @Autowired JdbcTemplate jdbc;
    @Autowired DealHealthJob job;

    private static Timestamp daysAgo(Instant now, long days) {
        // +12h keeps whole-day counts stable whatever the JVM/DB time-zone offset of a raw JDBC TIMESTAMP
        return Timestamp.from(now.minus(Duration.ofDays(days).plusHours(12)));
    }

    private static Map<String, JsonNode> factors(JsonNode opp) {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode f : opp.path("healthFactors")) out.put(f.path("key").asText(), f);
        return out;
    }

    private List<AppNotification> risk(User u, long oppId) {
        return notificationsOf(u).stream()
                .filter(n -> "deal_risk".equals(n.getType()) && Long.valueOf(oppId).equals(n.getRelatedId()))
                .toList();
    }

    @Test
    void healthyDealScoresFullMarksWithReasons() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long contactId = contact(rep, Map.of());
        long id = opp(rep, m("contactId", contactId, "value", 1000, "closeDate", LocalDate.now().plusDays(30).toString()));

        JsonNode o = getJson(rep, "/api/opportunities/" + id);
        assertThat(o.path("healthScore").asInt()).isEqualTo(100);
        assertThat(o.path("healthFactors").isArray()).isTrue();
        assertThat(factors(o).keySet()).contains("stage_time", "close_date", "activity", "contact", "overdue_tasks");
        for (JsonNode f : o.path("healthFactors")) {
            assertThat(f.path("points").asInt()).isZero();
            assertThat(f.path("detail").asText()).isNotBlank();
        }
        assertThat(o.path("healthFlags").isNull() || o.path("healthFlags").asText().isEmpty()).isTrue();

        // closed deals have no health score
        JsonNode won = body(patchAs(rep, "/api/opportunities/" + id, m("stage", "Won", "closeReason", "Signed")).andExpect(status().isOk()));
        assertThat(won.path("healthScore").isNull()).isTrue();
    }

    @Test
    void everyFactorContributesPointsAndRaisesFlagsOnce() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        Instant now = Instant.now();

        // org benchmark: three completed Proposal stints of 10 days → median 10
        for (int i = 0; i < 3; i++) {
            long b = opp(rep, m("stage", "Proposal", "contactId", contact(rep, Map.of())));
            patchAs(rep, "/api/opportunities/" + b, m("stage", "Negotiation")).andExpect(status().isOk());
            jdbc.update("UPDATE opportunity_stage_history SET seconds_in_from_stage = ? WHERE opportunity_id = ? AND from_stage = 'Proposal'", 10 * DAY, b);
        }

        long id = opp(rep, m("stage", "Proposal", "value", 1000, "closeDate", "2030-01-10")); // no contact
        patchAs(rep, "/api/opportunities/" + id, m("closeDate", "2030-02-10")).andExpect(status().isOk()); // slip 1
        patchAs(rep, "/api/opportunities/" + id, m("closeDate", "2030-03-10")).andExpect(status().isOk()); // slip 2
        JsonNode pulledIn = body(patchAs(rep, "/api/opportunities/" + id, m("closeDate", "2030-03-01")).andExpect(status().isOk()));
        assertThat(pulledIn.path("closeDateSlips").asInt()).isEqualTo(2); // pulling in is not a slip

        task(rep, m("relatedType", "Opportunity", "relatedId", id, "dueDate", LocalDate.now().minusDays(2).toString()));
        task(rep, m("relatedType", "Opportunity", "relatedId", id, "dueDate", LocalDate.now().plusDays(2).toString())); // not overdue
        long act = activity(rep, m("relatedType", "Opportunity", "relatedId", id, "subject", "Intro call"));
        jdbc.update("UPDATE activities SET created_at = ? WHERE id = ?", daysAgo(now, 20), act);
        jdbc.update("UPDATE opportunities SET created_at = ?, stage_entered_at = ? WHERE id = ?", daysAgo(now, 45), daysAgo(now, 40), id);

        job.recomputeAll(now);

        JsonNode o = getJson(rep, "/api/opportunities/" + id);
        Map<String, JsonNode> f = factors(o);
        assertThat(f.get("stage_time").path("points").asInt()).isEqualTo(-25);
        assertThat(f.get("stage_time").path("detail").asText()).contains("40 days").contains("Proposal");
        assertThat(f.get("close_date").path("points").asInt()).isEqualTo(-20);
        assertThat(f.get("activity").path("points").asInt()).isEqualTo(-15);
        assertThat(f.get("contact").path("points").asInt()).isEqualTo(-15);
        assertThat(f.get("proposal").path("points").asInt()).isEqualTo(-10);
        assertThat(f.get("overdue_tasks").path("points").asInt()).isEqualTo(-10);
        assertThat(o.path("healthScore").asInt()).isEqualTo(5);
        assertThat(o.path("healthFlags").asText().split(","))
                .containsExactlyInAnyOrder("STALLED_STAGE", "SLIPPED_CLOSE_DATE", "NO_ACTIVITY", "NO_CONTACT");

        // The slip flag was raised by the rep's own edit (the actor is never notified),
        // the other three by the nightly job: owner gets 3, the owner's manager all 4.
        assertThat(risk(rep, id)).hasSize(3);
        assertThat(risk(manager, id)).hasSize(4);

        // nothing changed → no repeat notifications
        job.recomputeAll(now);
        assertThat(risk(rep, id)).hasSize(3);
        assertThat(risk(manager, id)).hasSize(4);

        // clearing a flag and raising it again notifies again (once)
        long contactId = contact(manager, Map.of());
        JsonNode fixed = body(patchAs(manager, "/api/opportunities/" + id, m("contactId", contactId)).andExpect(status().isOk()));
        assertThat(fixed.path("healthFlags").asText()).doesNotContain("NO_CONTACT");
        assertThat(fixed.path("healthScore").asInt()).isEqualTo(20);
        jdbc.update("UPDATE opportunities SET contact_id = NULL WHERE id = ?", id);
        job.recomputeAll(now);
        job.recomputeAll(now);
        assertThat(risk(rep, id)).hasSize(4);
        assertThat(risk(manager, id)).hasSize(5);
    }

    @Test
    void overdueCloseDateAndMissingActivityOnOldDeal() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        Instant now = Instant.now();
        long contactId = contact(rep, Map.of());
        long id = opp(rep, m("contactId", contactId, "closeDate", LocalDate.now().minusDays(3).toString()));
        jdbc.update("UPDATE opportunities SET created_at = ?, stage_entered_at = ? WHERE id = ?", daysAgo(now, 35), daysAgo(now, 2), id);

        job.recomputeAll(now);

        JsonNode o = getJson(rep, "/api/opportunities/" + id);
        Map<String, JsonNode> f = factors(o);
        assertThat(f.get("close_date").path("points").asInt()).isEqualTo(-20);
        assertThat(f.get("close_date").path("detail").asText()).containsIgnoringCase("passed");
        assertThat(f.get("activity").path("points").asInt()).isEqualTo(-25);
        assertThat(f.get("activity").path("detail").asText()).containsIgnoringCase("no activity");
        assertThat(o.path("healthScore").asInt()).isEqualTo(55);
        assertThat(o.path("healthFlags").asText()).contains("SLIPPED_CLOSE_DATE").contains("NO_ACTIVITY");
    }
}
