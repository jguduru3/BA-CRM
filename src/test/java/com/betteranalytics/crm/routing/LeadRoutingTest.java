package com.betteranalytics.crm.routing;

import com.betteranalytics.crm.entity.ApiKey;
import com.betteranalytics.crm.entity.AppNotification;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.Team;
import com.betteranalytics.crm.entity.TeamMember;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AppNotificationRepository;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.repository.TeamMemberRepository;
import com.betteranalytics.crm.repository.TeamRepository;
import com.betteranalytics.crm.security.ApiKeyAuthFilter;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Round-robin lead routing: rotation, eligibility, territory, API intake, concurrency, settings access. */
class LeadRoutingTest extends IntegrationTest {

    @Autowired AuditEventRepository auditEventRepository;
    @Autowired AppNotificationRepository notificationRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired TeamMemberRepository teamMemberRepository;

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }

    private long ownerOfNewLead(User creator, Map<String, Object> extra) throws Exception {
        Map<String, Object> b = new HashMap<>(extra);
        b.putIfAbsent("name", "Inbound " + UUID.randomUUID());
        JsonNode lead = body(postAs(creator, "/api/leads", b).andExpect(status().isCreated()));
        return lead.path("ownerId").asLong();
    }

    private void enable(User manager, Map<String, Object> settings) throws Exception {
        Map<String, Object> b = new HashMap<>(settings);
        b.put("enabled", true);
        putAs(manager, "/api/routing-settings", b).andExpect(status().isOk());
    }

    private User inactive(User u) {
        u.setActive(false);
        return userRepository.save(u);
    }

    @Test
    void disabledByDefaultSoTheCreatorOwnsTheLead() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        user(org, Role.EXECUTIVE, manager.getId());
        assertThat(body(getAs(manager, "/api/routing-settings")).path("enabled").asBoolean()).isFalse();
        assertThat(ownerOfNewLead(manager, Map.of())).isEqualTo(manager.getId());
    }

    @Test
    void rotatesThroughActiveRepsSkippingInactiveAndNonReps() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User r1 = user(org, Role.EXECUTIVE, manager.getId());
        User r2 = user(org, Role.INSIDE_SALES, manager.getId());
        inactive(user(org, Role.EXECUTIVE, manager.getId()));
        User r3 = user(org, Role.FIELD_SALES, manager.getId());
        user(org, Role.SUPPORT);
        enable(manager, Map.of());

        List<Long> owners = new ArrayList<>();
        for (int i = 0; i < 6; i++) owners.add(ownerOfNewLead(manager, Map.of()));
        assertThat(owners).containsExactly(r1.getId(), r2.getId(), r3.getId(), r1.getId(), r2.getId(), r3.getId());

        assertThat(auditEventRepository.findByOrganizationIdAndAction(org, "auto_assign")).hasSize(6);
        List<AppNotification> inbox = notificationRepository
                .findByUserIdAndOrganizationIdOrderByReadAscCreatedAtDesc(r2.getId(), org);
        assertThat(inbox).hasSize(2);
        assertThat(inbox.get(0).getType()).isEqualTo("lead_assigned");
        assertThat(inbox.get(0).getBody()).contains("round-robin");

        // an explicit owner, and reps creating their own leads, are never routed
        assertThat(ownerOfNewLead(manager, Map.of("ownerId", r3.getId()))).isEqualTo(r3.getId());
        assertThat(ownerOfNewLead(r2, Map.of())).isEqualTo(r2.getId());
        assertThat(ownerOfNewLead(manager, Map.of())).isEqualTo(r1.getId()); // rotation unaffected
    }

    @Test
    void teamRotationOnlyUsesTheTeamsReps() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        User inTeam1 = user(org, Role.EXECUTIVE);
        User inTeam2 = user(org, Role.EXECUTIVE);
        user(org, Role.EXECUTIVE); // not on the team
        Team team = new Team();
        team.setOrganizationId(org);
        team.setName("North " + UUID.randomUUID());
        team.setLeaderId(vp.getId());
        teamRepository.save(team);
        for (User u : List.of(inTeam1, inTeam2, vp)) {
            TeamMember tm = new TeamMember();
            tm.setTeamId(team.getId());
            tm.setUserId(u.getId());
            teamMemberRepository.save(tm);
        }
        enable(vp, Map.of("teamId", team.getId()));

        JsonNode settings = body(getAs(vp, "/api/routing-settings"));
        assertThat(settings.path("teamId").asLong()).isEqualTo(team.getId());
        assertThat(settings.path("eligibleReps")).hasSize(2);

        Set<Long> owners = new HashSet<>();
        for (int i = 0; i < 4; i++) owners.add(ownerOfNewLead(vp, Map.of()));
        assertThat(owners).containsExactlyInAnyOrder(inTeam1.getId(), inTeam2.getId());

        // a team of another organization is rejected
        Long otherOrg = newOrg();
        Team foreign = new Team();
        foreign.setOrganizationId(otherOrg);
        foreign.setName("Foreign " + UUID.randomUUID());
        teamRepository.save(foreign);
        putAs(vp, "/api/routing-settings", m("teamId", foreign.getId())).andExpect(status().isBadRequest());
    }

    @Test
    void fieldSalesOnlyGetLeadsInTheirTerritory() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User exec = user(org, Role.EXECUTIVE, manager.getId());
        User field = user(org, Role.FIELD_SALES, manager.getId());
        field.setTerritory("Pune");
        userRepository.save(field);
        enable(manager, Map.of("respectTerritory", true));

        // Mumbai lead: the field rep is out of territory, so the executive takes every turn
        assertThat(ownerOfNewLead(manager, Map.of("geography", "Mumbai"))).isEqualTo(exec.getId());
        assertThat(ownerOfNewLead(manager, Map.of("geography", "Mumbai"))).isEqualTo(exec.getId());
        assertThat(ownerOfNewLead(manager, Map.of())).isEqualTo(exec.getId());
        // a Pune lead: the field rep is next after the executive
        assertThat(ownerOfNewLead(manager, Map.of("geography", "Pune West"))).isEqualTo(field.getId());

        putAs(manager, "/api/routing-settings", m("respectTerritory", false)).andExpect(status().isOk());
        assertThat(ownerOfNewLead(manager, Map.of("geography", "Mumbai"))).isEqualTo(exec.getId());
        assertThat(ownerOfNewLead(manager, Map.of("geography", "Mumbai"))).isEqualTo(field.getId());
    }

    @Test
    void apiIntakeIsRoutedWhateverTheKeysRole() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User integrationUser = user(org, Role.EXECUTIVE, manager.getId());
        User rep = user(org, Role.INSIDE_SALES, manager.getId());
        enable(manager, Map.of());

        JsonNode lead = body(mvc.perform(as(integrationUser, post("/api/leads"))
                        .requestAttr(ApiKeyAuthFilter.ATTR_API_KEY, new ApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(m("name", "Web form " + UUID.randomUUID()))))
                .andExpect(status().isCreated()));
        assertThat(lead.path("ownerId").asLong()).isEqualTo(integrationUser.getId()); // first rep by id
        JsonNode next = body(mvc.perform(as(integrationUser, post("/api/leads"))
                        .requestAttr(ApiKeyAuthFilter.ATTR_API_KEY, new ApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(m("name", "Web form " + UUID.randomUUID()))))
                .andExpect(status().isCreated()));
        assertThat(next.path("ownerId").asLong()).isEqualTo(rep.getId());
    }

    @Test
    void concurrentCreationsKeepAnExactRotation() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        List<User> reps = new ArrayList<>();
        for (int i = 0; i < 4; i++) reps.add(user(org, Role.EXECUTIVE, manager.getId()));
        enable(manager, Map.of());

        int leads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < leads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return ownerOfNewLead(manager, Map.of());
            }));
        }
        start.countDown();
        Map<Long, Integer> perRep = new HashMap<>();
        for (Future<Long> f : futures) perRep.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
        pool.shutdown();

        assertThat(perRep.keySet()).containsExactlyInAnyOrderElementsOf(reps.stream().map(User::getId).toList());
        assertThat(perRep.values()).allMatch(n -> n == leads / reps.size());
    }

    @Test
    void settingsAreManagerUpAndValidated() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        getAs(rep, "/api/routing-settings").andExpect(status().isForbidden());
        putAs(rep, "/api/routing-settings", m("enabled", true)).andExpect(status().isForbidden());
        putAs(manager, "/api/routing-settings", m("enabled", "yes")).andExpect(status().isBadRequest());
        putAs(manager, "/api/routing-settings", m("enabled", true, "teamId", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.teamId").isEmpty())
                .andExpect(jsonPath("$.eligibleReps.length()").value(1));
        assertThat(auditEventRepository.findByOrganizationIdAndAction(org, "update"))
                .anyMatch(e -> "routing_settings".equals(e.getResource()));
    }
}
