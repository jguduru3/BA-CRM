package com.betteranalytics.crm.admin;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminConsoleTest extends IntegrationTest {

    @Test
    void onlySuperAdminsReachTheConsole() throws Exception {
        Long org = newOrg();
        for (Role r : new Role[]{Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.SUPPORT}) {
            User u = user(org, r);
            getAs(u, "/api/admin/overview").andExpect(status().isForbidden());
            getAs(u, "/api/admin/users").andExpect(status().isForbidden());
            getAs(u, "/api/admin/system").andExpect(status().isForbidden());
            postAs(u, "/api/admin/users/" + u.getId() + "/force-logout", Map.of()).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void overviewCountsOnlyTheCallersOrganization() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        user(org, Role.EXECUTIVE);
        User gone = user(org, Role.EXECUTIVE);
        gone.setActive(false);
        userRepository.save(gone);
        postAs(admin, "/api/leads", Map.of("name", "Counted lead")).andExpect(status().isCreated());

        Long other = newOrg();
        User otherAdmin = user(other, Role.SUPER_ADMIN);
        postAs(otherAdmin, "/api/leads", Map.of("name", "Not counted")).andExpect(status().isCreated());

        JsonNode o = body(getAs(admin, "/api/admin/overview").andExpect(status().isOk()));
        assertThat(o.path("users").path("total").asLong()).isEqualTo(3);
        assertThat(o.path("users").path("active").asLong()).isEqualTo(2);
        assertThat(o.path("users").path("deactivated").asLong()).isEqualTo(1);
        assertThat(o.path("users").path("byRole").path("EXECUTIVE").asLong()).isEqualTo(1);
        assertThat(o.path("records").path("leads").asLong()).isEqualTo(1);
        assertThat(o.path("storage").path("backend").asText()).isEqualTo("local");
        assertThat(o.path("securityLast7Days").has("login_failed")).isTrue();
    }

    @Test
    void unlockClearsALockoutAndIsAudited() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User rep = user(org, Role.EXECUTIVE);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("email", rep.getEmail(), "password", "wrong-password-" + i))));
        }
        JsonNode list = body(getAs(admin, "/api/admin/users"));
        JsonNode row = null;
        for (JsonNode n : list) if (n.path("id").asLong() == rep.getId()) row = n;
        assertThat(row).isNotNull();
        assertThat(row.path("locked").asBoolean()).isTrue();
        assertThat(row.has("password")).isFalse();

        postAs(admin, "/api/admin/users/" + rep.getId() + "/unlock", Map.of()).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", rep.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk());

        JsonNode events = body(getAs(admin, "/api/admin/security-events?days=1"));
        assertThat(events.path("data").toString())
                .contains("login_failed").contains("account_locked").contains("account_unlocked");
        getAs(admin, "/api/admin/security-events?action=nonsense").andExpect(status().isBadRequest());
    }

    @Test
    void forceLogoutRevokesTheUsersSessions() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User rep = user(org, Role.EXECUTIVE);
        String repToken = token(rep);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + repToken)).andExpect(status().isOk());

        postAs(admin, "/api/admin/users/" + rep.getId() + "/force-logout", Map.of()).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + repToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void cannotActOnAnotherOrganizationsUsers() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        User foreign = user(newOrg(), Role.EXECUTIVE);
        postAs(admin, "/api/admin/users/" + foreign.getId() + "/unlock", Map.of()).andExpect(status().isNotFound());
        postAs(admin, "/api/admin/users/" + foreign.getId() + "/force-logout", Map.of()).andExpect(status().isNotFound());
    }

    @Test
    void systemReportsSchemaAndFeaturesWithoutSecrets() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String raw = getAs(admin, "/api/admin/system").andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").isString())
                .andExpect(jsonPath("$.pendingMigrations").value(0))
                .andExpect(jsonPath("$.features.fileStorage").value("Local disk"))
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("test-encryption-key").doesNotContain("test-secret").doesNotContain("whatsapp-app-secret");
    }

    @Test
    void lockedUntilInThePastIsNotReportedAsLocked() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User rep = user(org, Role.EXECUTIVE);
        rep.setLockedUntil(Instant.now().minus(1, ChronoUnit.HOURS));
        userRepository.save(rep);
        JsonNode o = body(getAs(admin, "/api/admin/overview"));
        assertThat(o.path("users").path("lockedNow").asLong()).isZero();
    }
}
