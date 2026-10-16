package com.betteranalytics.crm.master;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.DepartmentRepository;
import com.betteranalytics.crm.security.IdempotencyFilter;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Idempotency-Key through the Master API gateway (forward-based proxy and batch) over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MasterApiIdempotencyTest extends IntegrationTest {

    @Autowired DepartmentRepository departmentRepository;
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private HttpResponse<String> call(String apiKey, String path, String idemKey, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (idemKey != null) b.header(IdempotencyFilter.HEADER, idemKey);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String newKey(User admin) throws Exception {
        JsonNode b = body(postAs(admin, "/api/integrations/api-keys", Map.of("name", "idem", "scope", "read_write"))
                .andExpect(status().isCreated()));
        return b.path("key").asText();
    }

    @Test
    void proxiedCreateIsExecutedOnceAndReplayed() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String apiKey = newKey(admin);
        String idem = UUID.randomUUID().toString();
        String body = "{\"name\":\"Ops " + UUID.randomUUID().toString().substring(0, 6) + "\"}";

        HttpResponse<String> first = call(apiKey, "/api/master/v1/departments", idem, body);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(first.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).isEmpty();

        HttpResponse<String> retry = call(apiKey, "/api/master/v1/departments", idem, body);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).contains("true");
        assertThat(json.readTree(retry.body()).path("id").asLong()).isEqualTo(json.readTree(first.body()).path("id").asLong());
        assertThat(departmentRepository.findByOrganizationId(admin.getOrganizationId())).hasSize(1);

        HttpResponse<String> reused = call(apiKey, "/api/master/v1/departments", idem, "{\"name\":\"Different\"}");
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(json.readTree(reused.body()).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        // Keys belong to the credential: a second API key may use the same value.
        String otherKey = newKey(admin);
        assertThat(call(otherKey, "/api/master/v1/departments", idem, "{\"name\":\"Second\"}").statusCode()).isEqualTo(201);
    }

    @Test
    void aBatchIsIdempotentAsAWhole() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        String apiKey = newKey(admin);
        String idem = "batch-" + UUID.randomUUID();
        String batch = """
                {"operations": [
                  {"id": "a", "method": "POST", "path": "/departments", "body": {"name": "A"}},
                  {"id": "b", "method": "POST", "path": "/departments", "body": {"name": "B of {{a.body.name}}"}}
                ]}""";
        HttpResponse<String> first = call(apiKey, "/api/master/v1/batch", idem, batch);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        HttpResponse<String> retry = call(apiKey, "/api/master/v1/batch", idem, batch);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue(IdempotencyFilter.REPLAYED_HEADER)).contains("true");
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(departmentRepository.findByOrganizationId(admin.getOrganizationId())).hasSize(2);
    }
}
