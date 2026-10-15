package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.entity.Workspace;
import com.betteranalytics.crm.repository.WorkspaceRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkspaceControllerTest extends IntegrationTest {

    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired JdbcTemplate jdbc;

    private long ws(User u, String name, boolean makeDefault) throws Exception {
        return idOf(postAs(u, "/api/workspaces", Map.of("name", name, "default", makeDefault)).andExpect(status().isCreated()));
    }

    private long defaults(Long orgId) {
        return workspaceRepository.findByOrganizationId(orgId).stream().filter(Workspace::isDefault).count();
    }

    @Test
    void firstWorkspaceBecomesDefaultAndPromotingMovesTheFlag() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long a = ws(admin, "Sales", false);
        assertThat(body(getAs(admin, "/api/workspaces/" + a)).path("default").asBoolean()).isTrue();
        long b = ws(admin, "Ops", false);
        assertThat(body(getAs(admin, "/api/workspaces/" + b)).path("default").asBoolean()).isFalse();
        long c = ws(admin, "Marketing", true);
        assertThat(defaults(admin.getOrganizationId())).isEqualTo(1);
        assertThat(body(getAs(admin, "/api/workspaces/default")).path("id").asLong()).isEqualTo(c);

        putAs(admin, "/api/workspaces/" + b, Map.of("default", true)).andExpect(status().isOk());
        assertThat(defaults(admin.getOrganizationId())).isEqualTo(1);
        JsonNode list = body(getAs(admin, "/api/workspaces"));
        assertThat(list.get(0).path("id").asLong()).isEqualTo(b); // default first
        assertThat(list.get(0).has("defaultOrgId")).isFalse();
    }

    @Test
    void theDefaultCannotBeUnsetOrDeletedWithoutAReplacement() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long a = ws(admin, "A", false);
        long b = ws(admin, "B", false);
        putAs(admin, "/api/workspaces/" + a, Map.of("default", false)).andExpect(status().isConflict());
        deleteAs(admin, "/api/workspaces/" + a).andExpect(status().isConflict());
        deleteAs(admin, "/api/workspaces/" + a + "?newDefaultId=" + a).andExpect(status().isConflict());
        deleteAs(admin, "/api/workspaces/" + a + "?newDefaultId=999999").andExpect(status().isBadRequest());

        putAs(admin, "/api/workspaces/" + a, Map.of("default", false, "newDefaultId", b)).andExpect(status().isOk());
        assertThat(body(getAs(admin, "/api/workspaces/default")).path("id").asLong()).isEqualTo(b);
        deleteAs(admin, "/api/workspaces/" + b + "?newDefaultId=" + a).andExpect(status().isOk());
        assertThat(body(getAs(admin, "/api/workspaces/default")).path("id").asLong()).isEqualTo(a);
        deleteAs(admin, "/api/workspaces/" + a).andExpect(status().isConflict()); // last one standing
        assertThat(defaults(admin.getOrganizationId())).isEqualTo(1);
    }

    @Test
    void theDatabaseRejectsASecondDefault() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        ws(admin, "A", false);
        long b = ws(admin, "B", false);
        // bypass the service entirely
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE workspaces SET is_default = TRUE, default_org_id = organization_id WHERE id = ?", b))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE workspaces SET is_default = TRUE WHERE id = ?", b))
                .isInstanceOf(DataIntegrityViolationException.class);
        Workspace w = workspaceRepository.findById(b).orElseThrow();
        w.setDefault(true);
        w.setUpdatedAt(Instant.now());
        assertThatThrownBy(() -> workspaceRepository.saveAndFlush(w)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void permissionsAndTenantIsolation() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User vp = user(org, Role.VP_SALES);
        User rep = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long a = ws(admin, "A", false);
        long b = ws(admin, "B", false);
        getAs(rep, "/api/workspaces").andExpect(status().isOk());
        getAs(support, "/api/workspaces").andExpect(status().isOk());
        postAs(rep, "/api/workspaces", Map.of("name", "x")).andExpect(status().isForbidden());
        postAs(support, "/api/workspaces", Map.of("name", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/workspaces/" + b, Map.of("name", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/workspaces/" + b).andExpect(status().isForbidden());
        deleteAs(vp, "/api/workspaces/" + b).andExpect(status().isForbidden()); // delete is Super Admin only
        putAs(vp, "/api/workspaces/" + b, Map.of("description", "edited")).andExpect(status().isOk());
        getAs(stranger, "/api/workspaces/" + a).andExpect(status().isNotFound());
        putAs(stranger, "/api/workspaces/" + a, Map.of("name", "x")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/workspaces/" + b).andExpect(status().isNotFound());
        assertThat(body(getAs(stranger, "/api/workspaces")).size()).isZero();
    }

    @Test
    void partialUpdateKeepsIconColourAndDefault() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        long a = idOf(postAs(admin, "/api/workspaces", Map.of("name", "A", "icon", "rocket", "color", "#000000")));
        JsonNode w = body(putAs(admin, "/api/workspaces/" + a, Map.of("name", "A2")).andExpect(status().isOk()));
        assertThat(w.path("icon").asText()).isEqualTo("rocket");
        assertThat(w.path("color").asText()).isEqualTo("#000000");
        assertThat(w.path("default").asBoolean()).isTrue();
    }
}
