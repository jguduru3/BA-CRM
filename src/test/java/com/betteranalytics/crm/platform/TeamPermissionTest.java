package com.betteranalytics.crm.platform;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/teams goes through PermissionService (TEAM): view Manager+, change VP / Super Admin. */
class TeamPermissionTest extends IntegrationTest {

    private long createTeam(User admin, String name) throws Exception {
        return idOf(postAs(admin, "/api/teams", Map.of("name", name)).andExpect(status().isCreated()));
    }

    @Test
    void managerUpCanViewRepsAndSupportCannot() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        long id = createTeam(admin, "North");

        for (Role r : new Role[]{Role.SUPER_ADMIN, Role.VP_SALES, Role.MANAGER}) {
            User u = user(org, r);
            getAs(u, "/api/teams").andExpect(status().isOk());
            getAs(u, "/api/teams/" + id).andExpect(status().isOk());
            getAs(u, "/api/teams/" + id + "/members").andExpect(status().isOk());
        }
        for (Role r : new Role[]{Role.EXECUTIVE, Role.INSIDE_SALES, Role.FIELD_SALES, Role.SUPPORT}) {
            User u = user(org, r);
            getAs(u, "/api/teams").andExpect(status().isForbidden());
            getAs(u, "/api/teams/" + id).andExpect(status().isForbidden());
        }
    }

    @Test
    void onlyVpAndSuperAdminChangeTeams() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User vp = user(org, Role.VP_SALES);
        User manager = user(org, Role.MANAGER);
        User support = user(org, Role.SUPPORT);
        User rep = user(org, Role.EXECUTIVE);

        long id = createTeam(vp, "VP team");
        for (User u : new User[]{manager, support, rep}) {
            postAs(u, "/api/teams", Map.of("name", "x")).andExpect(status().isForbidden());
            putAs(u, "/api/teams/" + id, Map.of("name", "renamed")).andExpect(status().isForbidden());
            postAs(u, "/api/teams/" + id + "/members", Map.of("userId", rep.getId())).andExpect(status().isForbidden());
            deleteAs(u, "/api/teams/" + id).andExpect(status().isForbidden());
        }
        postAs(admin, "/api/teams/" + id + "/members", Map.of("userId", rep.getId())).andExpect(status().isCreated());
        putAs(admin, "/api/teams/" + id, Map.of("name", "Renamed")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
        deleteAs(vp, "/api/teams/" + id).andExpect(status().isOk());
    }

    @Test
    void anotherOrganizationsTeamIsInvisible() throws Exception {
        Long orgA = newOrg();
        Long orgB = newOrg();
        long id = createTeam(user(orgA, Role.SUPER_ADMIN), "A-team");
        User outsider = user(orgB, Role.SUPER_ADMIN);

        getAs(outsider, "/api/teams/" + id).andExpect(status().isNotFound());
        putAs(outsider, "/api/teams/" + id, Map.of("name", "hijack")).andExpect(status().isNotFound());
        deleteAs(outsider, "/api/teams/" + id).andExpect(status().isNotFound());
        getAs(outsider, "/api/teams").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cannotAddAnotherOrganizationsUserToATeam() throws Exception {
        Long orgA = newOrg();
        User admin = user(orgA, Role.SUPER_ADMIN);
        long id = createTeam(admin, "Mixed");
        User foreign = user(newOrg(), Role.EXECUTIVE);
        int s = postAs(admin, "/api/teams/" + id + "/members", Map.of("userId", foreign.getId()))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(s).isIn(400, 403, 404);
    }
}
