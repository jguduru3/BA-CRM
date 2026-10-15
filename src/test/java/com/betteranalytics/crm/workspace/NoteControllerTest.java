package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NoteControllerTest extends IntegrationTest {

    @Test
    void otherOrganisationGets404() throws Exception {
        User owner = user(newOrg(), Role.EXECUTIVE);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long id = idOf(postAs(owner, "/api/notes", Map.of("body", "secret")).andExpect(status().isCreated()));
        getAs(stranger, "/api/notes/" + id).andExpect(status().isNotFound());
        putAs(stranger, "/api/notes/" + id, Map.of("body", "x")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/notes/" + id).andExpect(status().isNotFound());
        assertThat(body(getAs(stranger, "/api/notes")).path("count").asLong()).isZero();
    }

    @Test
    void repsEditOnlyTheirOwnNotesAndSupportNeverWrites() throws Exception {
        Long org = newOrg();
        User a = user(org, Role.EXECUTIVE);
        User b = user(org, Role.INSIDE_SALES);
        User support = user(org, Role.SUPPORT);
        long id = idOf(postAs(a, "/api/notes", Map.of("body", "mine")));
        getAs(b, "/api/notes/" + id).andExpect(status().isOk());
        putAs(b, "/api/notes/" + id, Map.of("body", "x")).andExpect(status().isForbidden());
        deleteAs(b, "/api/notes/" + id).andExpect(status().isForbidden());
        getAs(support, "/api/notes/" + id).andExpect(status().isOk());
        postAs(support, "/api/notes", Map.of("body", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/notes/" + id, Map.of("body", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/notes/" + id).andExpect(status().isForbidden());
    }

    @Test
    void partialUpdateKeepsPinAndTitle() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long id = idOf(postAs(u, "/api/notes", Map.of("title", "T", "body", "B", "pinned", true)));
        JsonNode n = body(putAs(u, "/api/notes/" + id, Map.of("body", "B2")).andExpect(status().isOk()));
        assertThat(n.path("pinned").asBoolean()).isTrue();
        assertThat(n.path("title").asText()).isEqualTo("T");
        assertThat(n.path("body").asText()).isEqualTo("B2");
    }

    @Test
    void searchAndPagingRunInTheDatabase() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        postAs(u, "/api/notes", Map.of("title", "Pricing", "body", "discuss 100%_off"));
        postAs(u, "/api/notes", Map.of("title", "Other", "body", "nothing"));
        postAs(u, "/api/notes", Map.of("title", "Third", "body", "pricing again"));
        assertThat(body(getAs(u, "/api/notes?q=pricing")).path("count").asLong()).isEqualTo(2);
        // LIKE wildcards in the search text are matched literally
        assertThat(body(mvc.perform(as(u, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/notes").param("q", "100%_")))).path("count").asLong()).isEqualTo(1);
        assertThat(body(mvc.perform(as(u, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/notes").param("q", "%")))).path("count").asLong()).isEqualTo(1);
        JsonNode page = body(getAs(u, "/api/notes?size=1"));
        assertThat(page.path("count").asLong()).isEqualTo(3);
        assertThat(page.path("data").size()).isEqualTo(1);
    }
}
