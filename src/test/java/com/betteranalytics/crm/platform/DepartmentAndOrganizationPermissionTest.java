package com.betteranalytics.crm.platform;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/departments (DEPARTMENT) and /api/organizations/me (ORG_SETTINGS) go through PermissionService. */
class DepartmentAndOrganizationPermissionTest extends IntegrationTest {

    @Test
    void everyoneReadsDepartmentsOnlySuperAdminChangesThem() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        long id = idOf(postAs(admin, "/api/departments", Map.of("name", "Sales")).andExpect(status().isCreated()));

        for (Role r : Role.values()) {
            User u = user(org, r);
            getAs(u, "/api/departments").andExpect(status().isOk());
            getAs(u, "/api/departments/" + id).andExpect(status().isOk());
            if (r == Role.SUPER_ADMIN) continue;
            postAs(u, "/api/departments", Map.of("name", "x")).andExpect(status().isForbidden());
            putAs(u, "/api/departments/" + id, Map.of("name", "y")).andExpect(status().isForbidden());
            deleteAs(u, "/api/departments/" + id).andExpect(status().isForbidden());
        }
        putAs(admin, "/api/departments/" + id, Map.of("name", "Revenue")).andExpect(status().isOk());
        deleteAs(admin, "/api/departments/" + id).andExpect(status().isOk());
    }

    @Test
    void anotherOrganizationsDepartmentIsInvisible() throws Exception {
        long id = idOf(postAs(user(newOrg(), Role.SUPER_ADMIN), "/api/departments", Map.of("name", "Secret"))
                .andExpect(status().isCreated()));
        User outsider = user(newOrg(), Role.SUPER_ADMIN);
        getAs(outsider, "/api/departments/" + id).andExpect(status().isNotFound());
        putAs(outsider, "/api/departments/" + id, Map.of("name", "x")).andExpect(status().isNotFound());
        deleteAs(outsider, "/api/departments/" + id).andExpect(status().isNotFound());
        getAs(outsider, "/api/departments").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void organizationProfileReadByAllEditedBySuperAdminOnly() throws Exception {
        Long org = newOrg();
        for (Role r : Role.values()) {
            User u = user(org, r);
            getAs(u, "/api/organizations/me").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(org));
            if (r != Role.SUPER_ADMIN) {
                putAs(u, "/api/organizations/me", Map.of("name", "Nope")).andExpect(status().isForbidden());
            }
        }
        putAs(user(org, Role.SUPER_ADMIN), "/api/organizations/me", Map.of("name", "Renamed Org", "plan", "enterprise"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed Org"));
    }

    @Test
    void organizationEndpointOnlyEverTouchesTheCallersOwnOrg() throws Exception {
        Long orgA = newOrg();
        Long orgB = newOrg();
        putAs(user(orgB, Role.SUPER_ADMIN), "/api/organizations/me", Map.of("id", orgA, "name", "Pwned"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(orgB));
        getAs(user(orgA, Role.EXECUTIVE), "/api/organizations/me")
                .andExpect(jsonPath("$.name").value(org.hamcrest.Matchers.not("Pwned")));
    }
}
