package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.TodoItemRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoControllerTest extends IntegrationTest {

    @Autowired TodoItemRepository itemRepo;

    private long list(User u, boolean shared) throws Exception {
        return idOf(postAs(u, "/api/todos/lists", Map.of("name", "List", "shared", shared)).andExpect(status().isCreated()));
    }

    @Test
    void otherOrganisationGets404() throws Exception {
        User owner = user(newOrg(), Role.EXECUTIVE);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long id = list(owner, true);
        getAs(stranger, "/api/todos/lists/" + id + "/items").andExpect(status().isNotFound());
        putAs(stranger, "/api/todos/lists/" + id, Map.of("name", "x")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/todos/lists/" + id).andExpect(status().isNotFound());
        postAs(stranger, "/api/todos/lists/" + id + "/items", Map.of("title", "x")).andExpect(status().isNotFound());
    }

    @Test
    void privateListIsInvisibleToColleagues() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User peer = user(org, Role.EXECUTIVE);
        long id = list(owner, false);
        getAs(peer, "/api/todos/lists/" + id + "/items").andExpect(status().isNotFound());
        assertThat(body(getAs(peer, "/api/todos/lists")).toString()).doesNotContain("\"id\":" + id + ",");
    }

    @Test
    void supportCanReadSharedListsButNeverWrite() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        long id = list(owner, true);
        long item = idOf(postAs(owner, "/api/todos/lists/" + id + "/items", Map.of("title", "t")).andExpect(status().isCreated()));

        getAs(support, "/api/todos/lists").andExpect(status().isOk());
        getAs(support, "/api/todos/lists/" + id + "/items").andExpect(status().isOk());
        postAs(support, "/api/todos/lists", Map.of("name", "mine")).andExpect(status().isForbidden());
        postAs(support, "/api/todos/lists/" + id + "/items", Map.of("title", "x")).andExpect(status().isForbidden());
        putAs(support, "/api/todos/items/" + item, Map.of("title", "x")).andExpect(status().isForbidden());
        postAs(support, "/api/todos/items/" + item + "/toggle", Map.of()).andExpect(status().isForbidden());
        deleteAs(support, "/api/todos/items/" + item).andExpect(status().isForbidden());
        putAs(support, "/api/todos/lists/" + id, Map.of("name", "x")).andExpect(status().isForbidden());
        deleteAs(support, "/api/todos/lists/" + id).andExpect(status().isForbidden());
    }

    @Test
    void sharedListCanOnlyBeRenamedByOwnerOrManager() throws Exception {
        Long org = newOrg();
        User owner = user(org, Role.EXECUTIVE);
        User peer = user(org, Role.FIELD_SALES);
        User manager = user(org, Role.MANAGER);
        long id = list(owner, true);
        putAs(peer, "/api/todos/lists/" + id, Map.of("name", "x")).andExpect(status().isForbidden());
        putAs(manager, "/api/todos/lists/" + id, Map.of("name", "renamed")).andExpect(status().isOk());
        // peers may still work on items in a shared list
        postAs(peer, "/api/todos/lists/" + id + "/items", Map.of("title", "peer item")).andExpect(status().isCreated());
    }

    @Test
    void partialUpdateKeepsUntouchedFields() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long listId = idOf(postAs(u, "/api/todos/lists", Map.of("name", "L", "color", "#123456", "position", 3, "shared", true)));
        putAs(u, "/api/todos/lists/" + listId, Map.of("name", "L2")).andExpect(status().isOk());
        JsonNode lists = body(getAs(u, "/api/todos/lists"));
        JsonNode l = null;
        for (JsonNode n : lists) if (n.path("id").asLong() == listId) l = n;
        assertThat(l).isNotNull();
        assertThat(l.path("color").asText()).isEqualTo("#123456");
        assertThat(l.path("position").asInt()).isEqualTo(3);
        assertThat(l.path("shared").asBoolean()).isTrue();

        long item = idOf(postAs(u, "/api/todos/lists/" + listId + "/items",
                Map.of("title", "T", "pinned", true, "position", 7, "priority", "high", "notes", "keep me")));
        JsonNode updated = body(putAs(u, "/api/todos/items/" + item, Map.of("title", "T2")).andExpect(status().isOk()));
        assertThat(updated.path("title").asText()).isEqualTo("T2");
        assertThat(updated.path("pinned").asBoolean()).isTrue();
        assertThat(updated.path("position").asInt()).isEqualTo(7);
        assertThat(updated.path("priority").asText()).isEqualTo("high");
        assertThat(updated.path("notes").asText()).isEqualTo("keep me");
    }

    @Test
    void deletingAnItemDeletesItsSubItemsAndDeletingAListNever500s() throws Exception {
        User u = user(newOrg(), Role.EXECUTIVE);
        long listId = list(u, false);
        long parent = idOf(postAs(u, "/api/todos/lists/" + listId + "/items", Map.of("title", "parent")));
        long child = idOf(postAs(u, "/api/todos/lists/" + listId + "/items", Map.of("title", "child", "parentId", parent)));
        long grandChild = idOf(postAs(u, "/api/todos/lists/" + listId + "/items", Map.of("title", "gc", "parentId", child)));
        deleteAs(u, "/api/todos/items/" + parent).andExpect(status().isOk());
        assertThat(itemRepo.findById(child)).isEmpty();
        assertThat(itemRepo.findById(grandChild)).isEmpty();

        long p2 = idOf(postAs(u, "/api/todos/lists/" + listId + "/items", Map.of("title", "p2")));
        long c2 = idOf(postAs(u, "/api/todos/lists/" + listId + "/items", Map.of("title", "c2", "parentId", p2)));
        deleteAs(u, "/api/todos/lists/" + listId).andExpect(status().isOk());
        assertThat(itemRepo.findById(p2)).isEmpty();
        assertThat(itemRepo.findById(c2)).isEmpty();
    }
}
