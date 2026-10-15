package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.repository.OpportunityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Unassigned pool + atomic claim, idempotent conversion, score override, overdue follow-ups. */
class LeadWorkflowTest extends CrmTestSupport {

    @Autowired LeadRepository leadRepository;
    @Autowired OpportunityRepository opportunityRepository;
    @Autowired ContactRepository contactRepository;
    @Autowired FollowUpOverdueJob followUpJob;

    private long poolLead(User manager) throws Exception {
        long id = lead(manager);
        patchAs(manager, "/api/leads/" + id, m("ownerId", null)).andExpect(status().isOk());
        return id;
    }

    // ── pool + claim ────────────────────────────────────────────────────────────

    @Test
    void poolListsUnownedLeadsAndClaimIsRecorded() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long pooled = poolLead(manager);
        lead(rep); // owned — not in the pool

        JsonNode pool = getJson(rep, "/api/leads?pool=true");
        assertThat(pool.path("count").asLong()).isEqualTo(1);
        assertThat(pool.path("data").get(0).path("id").asLong()).isEqualTo(pooled);

        postAs(rep, "/api/leads/" + pooled + "/claim", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(rep.getId()));
        assertThat(getJson(rep, "/api/leads?pool=true").path("count").asLong()).isZero();

        List<AuditEvent> claims = audits(org, "claim");
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).getResourceId()).isEqualTo(String.valueOf(pooled));
        assertThat(claims.get(0).getUserId()).isEqualTo(rep.getId());

        // a second claim on an owned lead is a conflict, and managers assign instead of claiming
        User rep2 = user(org, Role.EXECUTIVE, manager.getId());
        long pooled2 = poolLead(manager);
        postAs(rep, "/api/leads/" + pooled2 + "/claim", Map.of()).andExpect(status().isOk());
        postAs(rep2, "/api/leads/" + pooled2 + "/claim", Map.of()).andExpect(status().isConflict());
        postAs(manager, "/api/leads/" + poolLead(manager) + "/claim", Map.of()).andExpect(status().isForbidden());
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        List<User> reps = new ArrayList<>();
        for (int i = 0; i < 4; i++) reps.add(user(org, Role.EXECUTIVE, manager.getId()));
        long leadId = poolLead(manager);

        ExecutorService pool = Executors.newFixedThreadPool(reps.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (User r : reps) {
            String token = token(r);
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(MockMvcRequestBuilders
                                .post("/api/leads/" + leadId + "/claim").header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        Lead claimed = leadRepository.findById(leadId).orElseThrow();
        assertThat(reps.stream().map(User::getId)).contains(claimed.getOwnerId());
        assertThat(audits(org, "claim")).hasSize(1);
    }

    // ── conversion ──────────────────────────────────────────────────────────────

    @Test
    void convertCopiesCompanyContactValueAndIsIdempotent() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long companyId = company(rep, Map.of("name", "Globex"));
        long existingContact = contact(rep, m("email", "Buyer@Globex.example", "companyId", companyId));
        long leadId = lead(rep, m("email", "buyer@globex.example", "companyId", companyId, "value", 120_000));

        JsonNode first = body(postAs(rep, "/api/leads/" + leadId + "/convert", Map.of()).andExpect(status().isOk()));
        long oppId = first.path("opportunity").path("id").asLong();
        assertThat(first.path("created").asBoolean()).isTrue();
        assertThat(first.path("opportunity").path("companyId").asLong()).isEqualTo(companyId);
        assertThat(first.path("opportunity").path("contactId").asLong()).isEqualTo(existingContact);
        assertThat(first.path("opportunity").path("value").asLong()).isEqualTo(120_000);
        assertThat(first.path("opportunity").path("sourceLeadId").asLong()).isEqualTo(leadId);
        assertThat(first.path("lead").path("convertedOppId").asLong()).isEqualTo(oppId);
        assertThat(first.path("lead").path("stage").asText()).isEqualTo("Won");

        JsonNode again = body(postAs(rep, "/api/leads/" + leadId + "/convert", Map.of()).andExpect(status().isOk()));
        assertThat(again.path("opportunity").path("id").asLong()).isEqualTo(oppId);
        assertThat(again.path("created").asBoolean()).isFalse();
        assertThat(opportunityRepository.findByOrganizationId(org)).hasSize(1);
    }

    @Test
    void convertCreatesContactAndCompanyWhenMissing() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long leadId = lead(rep, m("email", "new@initech.example", "businessName", "Initech", "decisionMaker", "Bill L"));
        JsonNode res = body(postAs(rep, "/api/leads/" + leadId + "/convert", Map.of()).andExpect(status().isOk()));
        long contactId = res.path("contactId").asLong();
        var contact = contactRepository.findById(contactId).orElseThrow();
        assertThat(contact.getEmail()).isEqualTo("new@initech.example");
        assertThat(contact.getName()).isEqualTo("Bill L");
        assertThat(contact.getCompanyId()).isNotNull();
        assertThat(res.path("opportunity").path("companyId").asLong()).isEqualTo(contact.getCompanyId());
        assertThat(getJson(rep, "/api/companies/" + contact.getCompanyId()).path("name").asText()).isEqualTo("Initech");
    }

    @Test
    void concurrentConversionsCreateOneOpportunity() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        long leadId = lead(rep, m("value", 10));
        String token = token(rep);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(pool.submit(() -> {
                start.await();
                var res = mvc.perform(MockMvcRequestBuilders
                        .post("/api/leads/" + leadId + "/convert").header("Authorization", "Bearer " + token)).andReturn();
                assertThat(res.getResponse().getStatus()).isEqualTo(200);
                return json.readTree(res.getResponse().getContentAsString()).path("opportunity").path("id").asText();
            }));
        }
        start.countDown();
        List<String> ids = new ArrayList<>();
        for (Future<String> f : results) ids.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(ids).allMatch(id -> id.equals(ids.get(0)));
        assertThat(opportunityRepository.findByOrganizationId(org)).hasSize(1);
    }

    // ── score override ──────────────────────────────────────────────────────────

    @Test
    void scoreOverrideNeedsAReasonAndIsAudited() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        long leadId = lead(rep);
        postAs(rep, "/api/leads/" + leadId + "/score-override", m("score", 90, "reason", "hot")).andExpect(status().isForbidden());
        postAs(manager, "/api/leads/" + leadId + "/score-override", m("score", 90)).andExpect(status().isBadRequest());
        postAs(manager, "/api/leads/" + leadId + "/score-override", m("score", 90, "reason", " ")).andExpect(status().isBadRequest());
        // a plain edit can't sneak a score change past the reason requirement
        patchAs(manager, "/api/leads/" + leadId, m("leadScore", 80)).andExpect(status().isBadRequest());
        patchAs(rep, "/api/leads/" + leadId, m("leadScore", 80)).andExpect(status().isForbidden());

        postAs(manager, "/api/leads/" + leadId + "/score-override", m("score", 90, "reason", "CFO asked for a demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadScore").value(90))
                .andExpect(jsonPath("$.leadScoreOverrideReason").value("CFO asked for a demo"));
        AuditEvent ev = auditEventRepository.findByOrganizationIdAndResource(org, "leads").stream()
                .filter(a -> a.getAfterJson() != null && a.getAfterJson().contains("score_override"))
                .findFirst().orElseThrow();
        assertThat(ev.getAfterJson()).contains("CFO asked for a demo");
        assertThat(ev.getUserId()).isEqualTo(manager.getId());
    }

    // ── follow-ups ──────────────────────────────────────────────────────────────

    @Test
    void rescheduleIsCountedAndOverdueOwnerIsNotifiedOnce() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        Instant due = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        long leadId = lead(rep, m("followUpAt", due.toString()));
        long openLead = lead(rep, m("followUpAt", due.plus(30, ChronoUnit.DAYS).toString()));

        // "now" is simulated past the due date (the real scheduler can't race the test)
        Instant later = due.plus(1, ChronoUnit.HOURS);
        followUpJob.notifyOverdue(later);
        followUpJob.notifyOverdue(later.plus(15, ChronoUnit.MINUTES));
        assertThat(overdueNotices(rep, leadId)).isEqualTo(1);
        assertThat(overdueNotices(rep, openLead)).isZero();

        // moving the date re-arms the reminder and counts as a reschedule
        Instant moved = due.plus(2, ChronoUnit.DAYS);
        JsonNode l = body(patchAs(rep, "/api/leads/" + leadId, m("followUpAt", moved.toString())).andExpect(status().isOk()));
        assertThat(l.path("followUpRescheduleCount").asInt()).isEqualTo(1);
        assertThat(leadRepository.findById(leadId).orElseThrow().getFollowUpNotifiedAt()).isNull();
        followUpJob.notifyOverdue(later); // not due yet
        assertThat(overdueNotices(rep, leadId)).isEqualTo(1);
        followUpJob.notifyOverdue(moved.plus(1, ChronoUnit.MINUTES));
        assertThat(overdueNotices(rep, leadId)).isEqualTo(2);

        // closed leads are not chased
        patchAs(rep, "/api/leads/" + openLead, m("stage", "Lost")).andExpect(status().isOk());
        followUpJob.notifyOverdue(due.plus(60, ChronoUnit.DAYS));
        assertThat(overdueNotices(rep, openLead)).isZero();
    }

    private long overdueNotices(User u, long leadId) {
        return notificationsOf(u).stream()
                .filter(n -> "follow_up_overdue".equals(n.getType()) && Long.valueOf(leadId).equals(n.getRelatedId()))
                .count();
    }
}
