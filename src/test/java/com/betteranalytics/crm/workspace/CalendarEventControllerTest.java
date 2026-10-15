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

class CalendarEventControllerTest extends IntegrationTest {

    private long event(User u, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "Call", "startAt", "2030-05-01T10:00:00Z"));
        body.putAll(extra);
        return idOf(postAs(u, "/api/calendar", body).andExpect(status().isCreated()));
    }

    @Test
    void otherOrganisationGets404() throws Exception {
        User owner = user(newOrg(), Role.EXECUTIVE);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long id = event(owner, Map.of());
        getAs(stranger, "/api/calendar/" + id).andExpect(status().isNotFound());
        putAs(stranger, "/api/calendar/" + id, Map.of("title", "x")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/calendar/" + id).andExpect(status().isNotFound());
        assertThat(body(getAs(stranger, "/api/calendar")).size()).isZero();
    }

    @Test
    void repsSeeOnlyTheirOwnCalendarAndManagersSeeAll() throws Exception {
        Long org = newOrg();
        User a = user(org, Role.EXECUTIVE);
        User b = user(org, Role.INSIDE_SALES);
        User manager = user(org, Role.MANAGER);
        long id = event(a, Map.of());
        event(b, Map.of());
        getAs(b, "/api/calendar/" + id).andExpect(status().isForbidden());
        putAs(b, "/api/calendar/" + id, Map.of("title", "x")).andExpect(status().isForbidden());
        deleteAs(b, "/api/calendar/" + id).andExpect(status().isForbidden());
        assertThat(body(getAs(b, "/api/calendar")).size()).isEqualTo(1);
        assertThat(body(getAs(manager, "/api/calendar")).size()).isEqualTo(2);
        // a rep cannot put an event on a colleague's calendar; a manager can
        postAs(b, "/api/calendar", Map.of("title", "x", "startAt", "2030-05-01T10:00:00Z", "ownerId", a.getId()))
                .andExpect(status().isForbidden());
        postAs(manager, "/api/calendar", Map.of("title", "x", "startAt", "2030-05-01T10:00:00Z", "ownerId", a.getId()))
                .andExpect(status().isCreated());
    }

    @Test
    void supportReadsOwnButNeverWrites() throws Exception {
        Long org = newOrg();
        User support = user(org, Role.SUPPORT);
        User manager = user(org, Role.MANAGER);
        long id = event(manager, Map.of("ownerId", support.getId()));
        getAs(support, "/api/calendar/" + id).andExpect(status().isOk());
        getAs(support, "/api/calendar").andExpect(status().isOk());
        postAs(support, "/api/calendar", Map.of("title", "x", "startAt", "2030-05-01T10:00:00Z")).andExpect(status().isForbidden());
        putAs(support, "/api/calendar/" + id, Map.of("title", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/calendar/" + id).andExpect(status().isForbidden());
    }

    @Test
    void partialUpdateKeepsAllDayTypeAndColour() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long id = event(u, Map.of("allDay", true, "eventType", "deadline", "colorHex", "#ff0000",
                "location", "HQ", "description", "d", "endAt", "2030-05-02T10:00:00Z"));
        JsonNode e = body(putAs(u, "/api/calendar/" + id, Map.of("title", "Renamed")).andExpect(status().isOk()));
        assertThat(e.path("allDay").asBoolean()).isTrue();
        assertThat(e.path("eventType").asText()).isEqualTo("deadline");
        assertThat(e.path("colorHex").asText()).isEqualTo("#ff0000");
        assertThat(e.path("location").asText()).isEqualTo("HQ");
        assertThat(e.path("description").asText()).isEqualTo("d");
        assertThat(e.path("endAt").asText()).startsWith("2030-05-02");
    }

    @Test
    void rangeFilterReturnsOverlappingEvents() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        event(u, Map.of("startAt", "2030-04-28T10:00:00Z", "endAt", "2030-05-03T10:00:00Z")); // spans into May
        event(u, Map.of("startAt", "2030-05-10T10:00:00Z"));
        event(u, Map.of("startAt", "2030-06-10T10:00:00Z"));
        JsonNode may = body(getAs(u, "/api/calendar?from=2030-05-01T00:00:00Z&to=2030-05-31T23:59:59Z"));
        assertThat(may.size()).isEqualTo(2);
    }
}
