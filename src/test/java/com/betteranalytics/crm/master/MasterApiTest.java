package com.betteranalytics.crm.master;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.DepartmentRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Master API gateway: keys, scopes, permission parity, blocked endpoints and
 * batches. The gateway forwards through the servlet container, which MockMvc
 * does not emulate, so gateway calls go over real HTTP to an embedded server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasterApiTest extends IntegrationTest {

    @Autowired DepartmentRepository departmentRepository;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private record Res(int status, String body, JsonNode json) {}

    private Res call(String method, String path, String headerName, String headerValue, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) b.header("Content-Type", "application/json");
        if (headerName != null) b.header(headerName, headerValue);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = json.nullNode();
        try {
            if (!r.body().isEmpty()) node = json.readTree(r.body());
        } catch (Exception ignored) {
            // non-JSON body
        }
        return new Res(r.statusCode(), r.body(), node);
    }

    private Res key(String key, String method, String path) throws Exception {
        return call(method, path, "X-API-Key", key, null);
    }

    private Res key(String key, String method, String path, String jsonBody) throws Exception {
        return call(method, path, "X-API-Key", key, jsonBody);
    }

    private String newKey(User admin, String scope, Long actingUserId) throws Exception {
        Map<String, Object> req = actingUserId == null
                ? Map.of("name", "test key", "scope", scope)
                : Map.of("name", "test key", "scope", scope, "actingUserId", actingUserId);
        JsonNode b = body(postAs(admin, "/api/integrations/api-keys", req).andExpect(status().isCreated()));
        String key = b.path("key").asText();
        assertThat(key).startsWith("bcrm_");
        return key;
    }

    @Test
    void catalogueDescribesTheApiAndHidesBlockedEndpoints() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String key = newKey(admin, "read", null);
        Res res = key(key, "GET", "/api/master/v1");
        assertThat(res.status()).isEqualTo(200);
        JsonNode cat = res.json();
        assertThat(cat.path("baseUrl").asText()).isEqualTo("/api/master/v1");
        assertThat(cat.path("operationCount").asInt()).isGreaterThan(20);
        JsonNode resources = cat.path("resources");
        assertThat(resources.has("teams")).isTrue();
        assertThat(resources.has("departments")).isTrue();
        assertThat(resources.has("auth")).isFalse();
        assertThat(resources.has("integrations")).isFalse();
        assertThat(resources.has("master")).isFalse();
        assertThat(cat.path("webhookEvents").toString()).contains("lead.created");

        Res me = key(key, "GET", "/api/master/v1/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().path("credential").asText()).isEqualTo("api_key");
        assertThat(me.json().path("scope").asText()).isEqualTo("read");
    }

    @Test
    void keysAreOnlyAcceptedUnderTheMasterApi() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String key = newKey(admin, "read_write", null);
        assertThat(key(key, "GET", "/api/departments").status()).isEqualTo(401);
        assertThat(call("GET", "/api/departments", "Authorization", "Bearer " + key, null).status()).isEqualTo(401);
        assertThat(key(key, "GET", "/api/master/v1/departments").status()).isEqualTo(200);
        assertThat(call("GET", "/api/master/v1/departments", "Authorization", "Bearer " + key, null).status()).isEqualTo(200);
        Res bad = key("bcrm_notARealKey0000000000000000000000000000", "GET", "/api/master/v1/departments");
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.json().path("code").asText()).isEqualTo("INVALID_API_KEY");
    }

    @Test
    void readOnlyKeyCannotWrite() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String key = newKey(admin, "read", null);
        assertThat(key(key, "POST", "/api/master/v1/departments", "{\"name\":\"X\"}").status()).isEqualTo(403);
        assertThat(key(key, "DELETE", "/api/master/v1/departments/1").status()).isEqualTo(403);
        assertThat(key(key, "POST", "/api/master/v1/batch",
                "{\"operations\":[{\"method\":\"POST\",\"path\":\"/departments\",\"body\":{\"name\":\"X\"}}]}").status())
                .isEqualTo(403);
        assertThat(departmentRepository.findByOrganizationId(admin.getOrganizationId())).isEmpty();
    }

    @Test
    void permissionsAreIdenticalThroughTheGateway() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        User rep = user(org, Role.EXECUTIVE);
        User manager = user(org, Role.MANAGER);

        // direct JWT and the gateway agree for the same acting user
        getAs(rep, "/api/teams").andExpect(status().isForbidden());
        assertThat(key(newKey(admin, "read_write", rep.getId()), "GET", "/api/master/v1/teams").status()).isEqualTo(403);
        getAs(manager, "/api/teams").andExpect(status().isOk());
        String managerKey = newKey(admin, "read_write", manager.getId());
        assertThat(key(managerKey, "GET", "/api/master/v1/teams").status()).isEqualTo(200);
        assertThat(key(managerKey, "POST", "/api/master/v1/departments", "{\"name\":\"X\"}").status()).isEqualTo(403);

        // a signed-in user's JWT works on the gateway too, with the same rules
        assertThat(call("GET", "/api/master/v1/teams", "Authorization", "Bearer " + token(rep), null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/master/v1/teams", "Authorization", "Bearer " + token(manager), null).status()).isEqualTo(200);

        // tenant isolation holds through the gateway
        long foreignDept = idOf(postAs(user(newOrg(), Role.SUPER_ADMIN), "/api/departments", Map.of("name", "Foreign"))
                .andExpect(status().isCreated()));
        assertThat(key(newKey(admin, "read", null), "GET", "/api/master/v1/departments/" + foreignDept).status()).isEqualTo(404);
    }

    @Test
    void sensitiveEndpointsAreBlocked() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String key = newKey(admin, "read_write", null);
        for (String path : List.of("/auth/me", "/integrations", "/integrations/api-keys", "/master/v1", "/health")) {
            assertThat(key(key, "GET", "/api/master/v1" + path).status()).as(path).isEqualTo(403);
        }
        // blocked for keys, even though the acting user could call them directly
        assertThat(key(key, "GET", "/api/master/v1/settings/ai").status()).isEqualTo(403);
        assertThat(key(key, "POST", "/api/master/v1/users/" + admin.getId() + "/reset-password",
                "{\"newPassword\":\"Another-Pass-4471\"}").status()).isEqualTo(403);
        // path tricks are refused before reaching any controller
        assertThat(key(key, "GET", "/api/master/v1/departments/..%2Fintegrations").status()).isBetween(400, 404);
    }

    @Test
    void atomicBatchRollsBackEverything() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String key = newKey(admin, "read_write", null);
        String batch = """
                {"atomic": true, "operations": [
                  {"id": "a", "method": "POST", "path": "/departments", "body": {"name": "Will be rolled back"}},
                  {"id": "b", "method": "POST", "path": "/departments", "body": {"name": ""}}
                ]}""";
        Res res = key(key, "POST", "/api/master/v1/batch", batch);
        assertThat(res.status()).as(res.body()).isEqualTo(409);
        assertThat(res.json().path("rolledBack").asBoolean()).isTrue();
        assertThat(res.json().path("results").get(0).path("status").asInt()).isEqualTo(201);
        assertThat(res.json().path("results").get(1).path("status").asInt()).isEqualTo(400);
        assertThat(departmentRepository.findByOrganizationId(admin.getOrganizationId())).isEmpty();
    }

    @Test
    void nonAtomicBatchKeepsSuccessesAndSubstitutesReferences() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String key = newKey(admin, "read_write", null);
        String batch = """
                {"operations": [
                  {"id": "parent", "method": "POST", "path": "/departments", "body": {"name": "Parent"}},
                  {"id": "child", "method": "POST", "path": "/departments",
                   "body": {"name": "Child of {{parent.body.name}}", "parentId": "{{parent.body.id}}"}},
                  {"id": "read", "method": "GET", "path": "/departments/{{child.body.id}}"}
                ]}""";
        Res res = key(key, "POST", "/api/master/v1/batch", batch);
        assertThat(res.status()).as(res.body()).isEqualTo(200);
        assertThat(res.json().path("success").asBoolean()).isTrue();
        long parentId = res.json().path("results").get(0).path("body").path("id").asLong();
        JsonNode child = res.json().path("results").get(1).path("body");
        assertThat(child.path("parentId").asLong()).isEqualTo(parentId);
        assertThat(child.path("name").asText()).isEqualTo("Child of Parent");
        assertThat(res.json().path("results").get(2).path("body").path("id").asLong()).isEqualTo(child.path("id").asLong());
        assertThat(departmentRepository.findByOrganizationId(admin.getOrganizationId())).hasSize(2);

        String bad = """
                {"operations": [{"id": "x", "method": "GET", "path": "/departments/{{missing.body.id}}"}]}""";
        Res r2 = key(key, "POST", "/api/master/v1/batch", bad);
        assertThat(r2.json().path("results").get(0).path("status").asInt()).isEqualTo(400);
    }
}
