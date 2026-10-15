package com.betteranalytics.crm.core;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lists are filtered, sorted and paginated in the database, with record scope in
 * the query — so a list request costs a small, constant number of SQL statements
 * no matter how many rows the organisation has.
 */
class ListPaginationTest extends CrmTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    private static final int SEEDED = 5_000;

    /**
     * Hibernate statistics are switched on at runtime for this test rather than via
     * {@code @TestPropertySource}: a property source would fork a second Spring context
     * (and a second full boot) for one test; the counter is the same either way.
     */
    private Statistics statistics() {
        Statistics s = emf.unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        return s;
    }

    private void seedLeads(Long org, Long ownerA, Long ownerB, int n) {
        List<Object[]> rows = new ArrayList<>(n);
        Timestamp base = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        for (int i = 0; i < n; i++) {
            Long owner = i % 3 == 0 ? ownerB : (i % 50 == 1 ? null : ownerA);
            String stage = i % 7 == 0 ? "Contacted" : "New";
            rows.add(new Object[]{org, String.format("Seed lead %05d", i), "seed" + i + "@example.com", owner, stage,
                    (long) i * 10, new Timestamp(base.getTime() + i * 60_000L)});
        }
        jdbc.batchUpdate("INSERT INTO leads (organization_id, name, email, owner_id, stage, deal_value, created_at, updated_at) " +
                         "VALUES (?, ?, ?, ?, ?, ?, now(), ?)", rows);
    }

    @Test
    void fiveThousandLeadsArePaginatedWithABoundedNumberOfStatements() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User repA = user(org, Role.EXECUTIVE, manager.getId());
        User repB = user(org, Role.EXECUTIVE); // another team
        seedLeads(org, repA.getId(), repB.getId(), SEEDED);
        long visibleToRepA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leads WHERE organization_id = ? AND (owner_id = ? OR owner_id IS NULL)",
                Long.class, org, repA.getId());

        String token = token(repA);
        Statistics stats = statistics();
        stats.clear();
        String res = mvc.perform(MockMvcRequestBuilders
                        .get("/api/leads?page=2&size=25&sort=name,asc").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long statements = stats.getPrepareStatementCount();
        stats.setStatisticsEnabled(false);

        JsonNode page = json.readTree(res);
        assertThat(page.path("count").asLong()).isEqualTo(visibleToRepA);
        assertThat(page.path("data")).hasSize(25);
        assertThat(page.path("page").asInt()).isEqualTo(2);
        assertThat(page.path("size").asInt()).isEqualTo(25);
        for (JsonNode l : page.path("data")) {
            assertThat(l.path("ownerId").isNull() || l.path("ownerId").asLong() == repA.getId()).isTrue();
        }
        // auth user lookup + scope (reports, led teams, delegations) + page + count — never per-row work
        assertThat(statements).isLessThanOrEqualTo(12);

        // same budget for a Manager (TEAM scope) and for the reports dashboard
        String managerToken = token(manager);
        stats = statistics();
        stats.clear();
        mvc.perform(MockMvcRequestBuilders
                .get("/api/reports/dashboard").header("Authorization", "Bearer " + managerToken)).andExpect(status().isOk());
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(15);
        stats.setStatisticsEnabled(false);
    }

    @Test
    void sortingSearchFiltersAndLimits() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User repB = user(org, Role.EXECUTIVE);
        seedLeads(org, admin.getId(), repB.getId(), 300);

        JsonNode first = getJson(admin, "/api/leads?size=10&sort=name,asc");
        assertThat(first.path("count").asLong()).isEqualTo(300);
        assertThat(first.path("data").get(0).path("name").asText()).isEqualTo("Seed lead 00000");
        JsonNode desc = getJson(admin, "/api/leads?size=10&sort=-name");
        assertThat(desc.path("data").get(0).path("name").asText()).isEqualTo("Seed lead 00299");

        JsonNode byValue = getJson(admin, "/api/leads?size=1&sort=value,desc");
        assertThat(byValue.path("data").get(0).path("value").asLong()).isEqualTo(2990);

        assertThat(getJson(admin, "/api/leads?search=lead 0012").path("count").asLong()).isEqualTo(10);
        assertThat(getJson(admin, "/api/leads?stage=Contacted").path("count").asLong()).isEqualTo(43);
        assertThat(getJson(admin, "/api/leads?ownerId=" + repB.getId()).path("count").asLong()).isEqualTo(100);
        // the pool: every 50th lead (i % 50 == 1) that isn't repB's
        assertThat(getJson(admin, "/api/leads?pool=true").path("count").asLong()).isEqualTo(4);
        // LIKE wildcards in user input are literal
        assertThat(getJson(admin, "/api/leads?search=%").path("count").asLong()).isZero();

        // defaults and caps
        assertThat(getJson(admin, "/api/leads").path("data")).hasSize(50);
        assertThat(getJson(admin, "/api/leads?size=1000").path("data")).hasSize(200);
        assertThat(getJson(admin, "/api/leads?limit=250").path("data")).hasSize(250); // legacy param
        getAs(admin, "/api/leads?sort=password").andExpect(status().isBadRequest());
        getAs(admin, "/api/leads?sort=name,sideways").andExpect(status().isBadRequest());
        getAs(admin, "/api/leads?page=-1").andExpect(status().isBadRequest());

        // the other list endpoints share the same contract
        for (String path : new String[]{"/api/opportunities", "/api/companies", "/api/contacts", "/api/tasks", "/api/activities"}) {
            JsonNode p = getJson(admin, path + "?page=0&size=5");
            assertThat(p.has("count")).isTrue();
            assertThat(p.path("size").asInt()).isEqualTo(5);
            getAs(admin, path + "?sort=nope").andExpect(status().isBadRequest());
        }
    }

    @Test
    void listFiltersOnRelatedRecords() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        long leadA = lead(admin);
        long leadB = lead(admin);
        task(admin, m("relatedType", "Lead", "relatedId", leadA));
        task(admin, m("relatedType", "lead", "relatedId", leadB, "status", "Done"));
        activity(admin, m("relatedType", "Lead", "relatedId", leadA));
        activity(admin, m("relatedType", "Lead", "relatedId", leadA));

        assertThat(getJson(admin, "/api/tasks?relatedType=Lead&relatedId=" + leadA).path("count").asLong()).isEqualTo(1);
        assertThat(getJson(admin, "/api/tasks?status=Done").path("count").asLong()).isEqualTo(1);
        assertThat(getJson(admin, "/api/activities?relatedType=lead&relatedId=" + leadA).path("count").asLong()).isEqualTo(2);
        long companyId = company(admin, Map.of("name", "Filter Co"));
        contact(admin, m("companyId", companyId));
        contact(admin, Map.of());
        assertThat(getJson(admin, "/api/contacts?companyId=" + companyId).path("count").asLong()).isEqualTo(1);
        assertThat(getJson(admin, "/api/companies?search=filter").path("count").asLong()).isEqualTo(1);
    }
}
