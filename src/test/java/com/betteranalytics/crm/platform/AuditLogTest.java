package com.betteranalytics.crm.platform;

import com.betteranalytics.crm.entity.AuditEvent;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.AuditEventRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Audit log scoping (AUDIT_LOG rules), DB-side filtering/pagination, and the CSV export. */
class AuditLogTest extends IntegrationTest {

    @Autowired AuditEventRepository events;

    private AuditEvent event(User by, String action, String resource, String name) {
        AuditEvent e = new AuditEvent();
        e.setOrganizationId(by.getOrganizationId());
        e.setUserId(by.getId());
        e.setUserName(by.getName());
        e.setAction(action);
        e.setResource(resource);
        e.setResourceId("1");
        e.setResourceName(name);
        e.setCreatedAt(Instant.now());
        return events.save(e);
    }

    private Set<String> names(JsonNode body) {
        Set<String> out = new HashSet<>();
        body.path("data").forEach(n -> out.add(n.path("resourceName").asText()));
        return out;
    }

    @Test
    void seniorRolesSeeTheWholeOrgManagersOnlyTheirTeamOthersNothing() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User vp = user(org, Role.VP_SALES);
        User manager = user(org, Role.MANAGER);
        User myRep = user(org, Role.EXECUTIVE, manager.getId());
        User otherManager = user(org, Role.MANAGER);
        User otherRep = user(org, Role.EXECUTIVE, otherManager.getId());
        User support = user(org, Role.SUPPORT);

        event(manager, "update", "leads", "by-manager");
        event(myRep, "create", "leads", "by-my-rep");
        AuditEvent foreign = event(otherRep, "create", "leads", "by-other-rep");
        event(admin, "update", "users", "by-admin");
        event(user(newOrg(), Role.SUPER_ADMIN), "create", "leads", "other-tenant");

        for (User senior : new User[]{admin, vp}) {
            Set<String> seen = names(body(getAs(senior, "/api/audit-events?page=0&size=200").andExpect(status().isOk())));
            assertThat(seen).contains("by-manager", "by-my-rep", "by-other-rep", "by-admin").doesNotContain("other-tenant");
        }

        Set<String> managerSees = names(body(getAs(manager, "/api/audit-events?page=0&size=200").andExpect(status().isOk())));
        assertThat(managerSees).contains("by-manager", "by-my-rep")
                .doesNotContain("by-other-rep", "by-admin", "other-tenant");
        getAs(manager, "/api/audit-events/" + foreign.getId()).andExpect(status().isNotFound());
        getAs(otherManager, "/api/audit-events/" + foreign.getId()).andExpect(status().isOk());

        for (User none : new User[]{myRep, support, user(org, Role.INSIDE_SALES), user(org, Role.FIELD_SALES)}) {
            getAs(none, "/api/audit-events").andExpect(status().isForbidden());
            getAs(none, "/api/audit-events/export").andExpect(status().isForbidden());
            getAs(none, "/api/audit-events/facets").andExpect(status().isForbidden());
            getAs(none, "/api/audit-events/" + foreign.getId()).andExpect(status().isForbidden());
        }
    }

    @Test
    void filtersAndPaginationRunInTheDatabase() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        for (int i = 0; i < 7; i++) event(admin, "create", "companies", "Acme " + i);
        event(admin, "delete", "companies", "Globex");
        event(admin, "update", "leads", "Initech");

        JsonNode p0 = body(getAs(admin, "/api/audit-events?page=0&size=3&resource=companies").andExpect(status().isOk()));
        assertThat(p0.path("count").asLong()).isEqualTo(8);
        assertThat(p0.path("totalPages").asInt()).isEqualTo(3);
        assertThat(p0.path("data").size()).isEqualTo(3);
        JsonNode p2 = body(getAs(admin, "/api/audit-events?page=2&size=3&resource=companies"));
        assertThat(p2.path("data").size()).isEqualTo(2);

        assertThat(body(getAs(admin, "/api/audit-events?page=0&size=50&action=delete")).path("count").asLong()).isEqualTo(1);
        assertThat(names(body(getAs(admin, "/api/audit-events?page=0&size=50&q=initech")))).containsExactly("Initech");
        assertThat(body(getAs(admin, "/api/audit-events?page=0&size=50&q=%25")).path("count").asLong())
                .as("% is a literal, not a wildcard").isZero();
        getAs(admin, "/api/audit-events?from=2026-13-40").andExpect(status().isBadRequest());

        // legacy limit-style call still works
        getAs(admin, "/api/audit-events?limit=2").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));

        JsonNode facets = body(getAs(admin, "/api/audit-events/facets").andExpect(status().isOk()));
        assertThat(facets.path("resources").toString()).contains("companies", "leads");
        assertThat(facets.path("actions").toString()).contains("create", "delete", "update");
    }

    @Test
    void exportIsScopedFormulaSafeAndAudited() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE, manager.getId());
        User stranger = user(org, Role.EXECUTIVE);
        event(rep, "create", "leads", "=HYPERLINK(\"http://evil.example\",\"x\")");
        event(rep, "create", "leads", "Plain, with comma");
        event(stranger, "create", "leads", "stranger-row");

        var res = getAs(manager, "/api/audit-events/export?resource=leads")
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse();
        String csv = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("id,timestamp_utc,actor_id,actor_name,action,resource,record_id,record_name");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"http://evil.example\"\",\"\"x\"\")\"");
        assertThat(csv).contains("\"Plain, with comma\"");
        assertThat(csv).doesNotContain("stranger-row");
        assertThat(csv.lines().filter(l -> l.contains(",create,leads,")).count()).isEqualTo(2);

        JsonNode audit = body(getAs(admin, "/api/audit-events?page=0&size=50&action=export"));
        assertThat(audit.path("count").asLong()).isEqualTo(1);
        assertThat(audit.path("data").get(0).path("userId").asLong()).isEqualTo(manager.getId());
        assertThat(audit.path("data").get(0).path("resource").asText()).isEqualTo("audit_events");
    }
}
