package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentControllerTest extends IntegrationTest {

    @Test
    void otherOrganisationGets404() throws Exception {
        User owner = user(newOrg(), Role.EXECUTIVE);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long id = idOf(postAs(owner, "/api/documents", Map.of("title", "Plan")).andExpect(status().isOk()));
        getAs(stranger, "/api/documents/" + id).andExpect(status().isNotFound());
        putAs(stranger, "/api/documents/" + id, Map.of("title", "x")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/documents/" + id).andExpect(status().isNotFound());
        assertThat(body(getAs(stranger, "/api/documents")).size()).isZero();
    }

    @Test
    void supportReadsButNeverWritesAndRepsCannotDelete() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        long id = idOf(postAs(owner, "/api/documents", Map.of("title", "Plan")));
        getAs(support, "/api/documents/" + id).andExpect(status().isOk());
        postAs(support, "/api/documents", Map.of("title", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/documents/" + id, Map.of("title", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/documents/" + id).andExpect(status().isForbidden());
        // DOCUMENT delete is VP / Super Admin only
        deleteAs(owner, "/api/documents/" + id).andExpect(status().isForbidden());
    }

    @Test
    void partialUpdateKeepsContentAndLink() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long id = idOf(postAs(u, "/api/documents", Map.of("title", "Plan", "contentJson", "body text")));
        JsonNode d = body(putAs(u, "/api/documents/" + id, Map.of("title", "Plan v2")).andExpect(status().isOk()));
        assertThat(d.path("contentJson").asText()).isEqualTo("body text");
        assertThat(d.path("title").asText()).isEqualTo("Plan v2");
    }
}
