package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.Task;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.TaskRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectControllerTest extends IntegrationTest {

    @Autowired TaskRepository taskRepository;

    private long project(User u, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "Apollo"));
        body.putAll(extra);
        return idOf(postAs(u, "/api/projects", body).andExpect(status().isCreated()));
    }

    @Test
    void otherOrganisationGets404() throws Exception {
        User owner = user(newOrg(), Role.EXECUTIVE);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long id = project(owner, Map.of());
        getAs(stranger, "/api/projects/" + id).andExpect(status().isNotFound());
        putAs(stranger, "/api/projects/" + id, Map.of("name", "x")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/projects/" + id).andExpect(status().isNotFound());
        postAs(stranger, "/api/projects/" + id + "/milestones", Map.of("name", "m")).andExpect(status().isNotFound());
        assertThat(body(getAs(stranger, "/api/projects")).size()).isZero();
    }

    @Test
    void supportReadsButNeverWrites() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        long id = project(owner, Map.of());
        getAs(support, "/api/projects").andExpect(status().isOk());
        getAs(support, "/api/projects/" + id).andExpect(status().isOk());
        postAs(support, "/api/projects", Map.of("name", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/projects/" + id, Map.of("name", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/projects/" + id).andExpect(status().isForbidden());
        postAs(support, "/api/projects/" + id + "/milestones", Map.of("name", "m")).andExpect(status().isForbidden());
        // even as a project member, SUPPORT has no edit feature
        postAs(owner, "/api/projects/" + id + "/members", Map.of("userId", support.getId(), "role", "manager")).andExpect(status().isCreated());
        putAs(support, "/api/projects/" + id, Map.of("name", "x")).andExpect(status().isForbidden());
    }

    @Test
    void repsCanOnlyChangeTheirOwnProjectsUnlessMember() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User peer = user(org, Role.FIELD_SALES);
        User manager = user(org, Role.MANAGER);
        long id = project(owner, Map.of());
        putAs(peer, "/api/projects/" + id, Map.of("name", "x")).andExpect(status().isForbidden());
        deleteAs(peer, "/api/projects/" + id).andExpect(status().isForbidden());
        postAs(peer, "/api/projects/" + id + "/milestones", Map.of("name", "m")).andExpect(status().isForbidden());
        putAs(manager, "/api/projects/" + id, Map.of("name", "by manager")).andExpect(status().isOk());

        postAs(owner, "/api/projects/" + id + "/members", Map.of("userId", peer.getId(), "role", "member")).andExpect(status().isCreated());
        postAs(peer, "/api/projects/" + id + "/milestones", Map.of("name", "m")).andExpect(status().isCreated());
        putAs(peer, "/api/projects/" + id, Map.of("name", "x")).andExpect(status().isForbidden());
    }

    @Test
    void partialUpdateKeepsUntouchedFields() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long id = project(u, Map.of("description", "desc", "dueDate", "2030-01-02", "budget", 500,
                "tags", "a,b", "coverColor", "#abcdef", "priority", "high"));
        JsonNode p = body(putAs(u, "/api/projects/" + id, Map.of("status", "on_hold")).andExpect(status().isOk()));
        assertThat(p.path("status").asText()).isEqualTo("on_hold");
        assertThat(p.path("description").asText()).isEqualTo("desc");
        assertThat(p.path("dueDate").asText()).isEqualTo("2030-01-02");
        assertThat(p.path("budget").asLong()).isEqualTo(500);
        assertThat(p.path("tags").asText()).isEqualTo("a,b");
        assertThat(p.path("coverColor").asText()).isEqualTo("#abcdef");
        assertThat(p.path("priority").asText()).isEqualTo("high");

        long m = idOf(postAs(u, "/api/projects/" + id + "/milestones",
                Map.of("name", "M1", "position", 4, "completed", true, "dueDate", "2030-02-02")));
        JsonNode ms = body(putAs(u, "/api/projects/" + id + "/milestones/" + m, Map.of("name", "M1b")).andExpect(status().isOk()));
        assertThat(ms.path("position").asInt()).isEqualTo(4);
        assertThat(ms.path("completed").asBoolean()).isTrue();
        assertThat(ms.path("dueDate").asText()).isEqualTo("2030-02-02");
    }

    @Test
    void deletingAProjectWithMilestonesMembersAndTasksNever500s() throws Exception {
        Long org = newOrg();
        User u = user(org, Role.EXECUTIVE);
        User peer = user(org, Role.EXECUTIVE);
        long id = project(u, Map.of());
        postAs(u, "/api/projects/" + id + "/milestones", Map.of("name", "M")).andExpect(status().isCreated());
        postAs(u, "/api/projects/" + id + "/members", Map.of("userId", peer.getId())).andExpect(status().isCreated());
        Task t = new Task();
        t.setOrganizationId(org);
        t.setTitle("linked");
        t.setAssigneeId(u.getId());
        t.setProjectId(id);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        t = taskRepository.save(t);

        JsonNode summary = body(getAs(u, "/api/projects/summary?ids=" + id));
        assertThat(summary.path(String.valueOf(id)).path("milestones").asLong()).isEqualTo(1);
        assertThat(summary.path(String.valueOf(id)).path("members").asLong()).isEqualTo(2);
        assertThat(summary.path(String.valueOf(id)).path("tasks").asLong()).isEqualTo(1);

        deleteAs(u, "/api/projects/" + id).andExpect(status().isOk());
        getAs(u, "/api/projects/" + id).andExpect(status().isNotFound());
        assertThat(taskRepository.findById(t.getId()).orElseThrow().getProjectId()).isNull();
    }

    @Test
    void listFiltersAndPagesInTheDatabase() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        project(u, Map.of("name", "Alpha", "status", "active"));
        project(u, Map.of("name", "Beta", "status", "completed"));
        project(u, Map.of("name", "Gamma", "status", "active"));
        assertThat(body(getAs(u, "/api/projects?status=active")).size()).isEqualTo(2);
        assertThat(body(getAs(u, "/api/projects?q=bet")).size()).isEqualTo(1);
        JsonNode page = body(getAs(u, "/api/projects?sort=name,asc&size=2&page=1"));
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.get(0).path("name").asText()).isEqualTo("Gamma");
        getAs(u, "/api/projects?sort=password").andExpect(status().isBadRequest());
    }
}
