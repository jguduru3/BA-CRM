package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeetingControllerTest extends IntegrationTest {

    private long meeting(User u, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Kickoff", "startAt", "2030-05-01T10:00:00Z"));
        body.putAll(extra);
        return idOf(postAs(u, "/api/meetings", body).andExpect(status().isCreated()));
    }

    @Test
    void otherOrganisationGets404() throws Exception {
        User owner = user(newOrg(), Role.EXECUTIVE);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long id = meeting(owner, Map.of());
        getAs(stranger, "/api/meetings/" + id).andExpect(status().isNotFound());
        putAs(stranger, "/api/meetings/" + id, Map.of("title", "x")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/meetings/" + id).andExpect(status().isNotFound());
        assertThat(body(getAs(stranger, "/api/meetings")).path("count").asLong()).isZero();
    }

    @Test
    void repsCannotTouchColleaguesMeetingsAndSupportNeverWrites() throws Exception {
        Long org = newOrg();
        User a = user(org, Role.EXECUTIVE);
        User b = user(org, Role.FIELD_SALES);
        User support = user(org, Role.SUPPORT);
        long id = meeting(a, Map.of());
        getAs(b, "/api/meetings/" + id).andExpect(status().isForbidden());
        putAs(b, "/api/meetings/" + id, Map.of("title", "x")).andExpect(status().isForbidden());
        deleteAs(b, "/api/meetings/" + id).andExpect(status().isForbidden());
        getAs(support, "/api/meetings").andExpect(status().isOk());
        postAs(support, "/api/meetings", Map.of("title", "x", "startAt", "2030-05-01T10:00:00Z")).andExpect(status().isForbidden());
    }

    @Test
    void partialUpdateKeepsStatusAndOtherFields() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long id = meeting(u, Map.of("status", "completed", "location", "HQ", "notes", "n", "outcome", "won",
                "meetingUrl", "https://meet.example.com/abc"));
        JsonNode m = body(putAs(u, "/api/meetings/" + id, Map.of("title", "Renamed")).andExpect(status().isOk()));
        assertThat(m.path("status").asText()).isEqualTo("completed");
        assertThat(m.path("location").asText()).isEqualTo("HQ");
        assertThat(m.path("notes").asText()).isEqualTo("n");
        assertThat(m.path("outcome").asText()).isEqualTo("won");
        assertThat(m.path("meetingUrl").asText()).isEqualTo("https://meet.example.com/abc");
    }

    @Test
    void unsafeMeetingLinksAreRejected() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        postAs(u, "/api/meetings", Map.of("title", "x", "startAt", "2030-05-01T10:00:00Z", "meetingUrl", "javascript:alert(1)"))
                .andExpect(status().isBadRequest());
        long id = meeting(u, Map.of());
        putAs(u, "/api/meetings/" + id, Map.of("meetingUrl", "data:text/html,<script>")).andExpect(status().isBadRequest());
    }

    @Test
    void countIsTheTotalNotThePageSize() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        for (int i = 0; i < 3; i++) meeting(u, Map.of());
        JsonNode page = body(getAs(u, "/api/meetings?size=2"));
        assertThat(page.path("count").asLong()).isEqualTo(3);
        assertThat(page.path("data").size()).isEqualTo(2);
    }
}
