package com.betteranalytics.crm.master;

import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.service.WebhookService;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Outbound webhooks against a real local HTTP receiver. The test profile sets
 * app.outbound.allow-private-networks=true so 127.0.0.1 is an allowed target.
 */
class WebhookDeliveryTest extends IntegrationTest {

    private record Received(Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    private HttpServer server;
    private final LinkedBlockingQueue<Received> inbox = new LinkedBlockingQueue<>();

    @BeforeEach
    void startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            try (InputStream in = ex.getRequestBody()) {
                inbox.add(new Received(Map.copyOf(ex.getRequestHeaders()), new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private void assertSigned(Received r, String secret) {
        String ts = r.header("X-BetterCRM-Timestamp");
        assertThat(ts).isNotNull();
        assertThat(Math.abs(Instant.now().getEpochSecond() - Long.parseLong(ts))).isLessThan(120);
        assertThat(r.header("X-BetterCRM-Signature"))
                .isEqualTo(WebhookService.sign(secret, Long.parseLong(ts), r.body()))
                .startsWith("sha256=");
        assertThat(r.header("X-BetterCRM-Signature")).isNotEqualTo(WebhookService.sign("wrong-secret", Long.parseLong(ts), r.body()));
        assertThat(r.header("X-BetterCRM-Delivery")).isNotBlank();
    }

    @Test
    void testEventIsDeliveredWithAVerifiableSignature() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        JsonNode hook = body(postAs(admin, "/api/integrations/webhooks",
                Map.of("name", "receiver", "url", url(), "events", List.of("*"))).andExpect(status().isCreated()));
        String secret = hook.path("secret").asText();
        assertThat(secret).startsWith("whsec_");

        JsonNode d = body(postAs(admin, "/api/integrations/webhooks/" + hook.path("id").asLong() + "/test", Map.of())
                .andExpect(status().isOk()));
        assertThat(d.path("success").asBoolean()).isTrue();
        assertThat(d.path("statusCode").asInt()).isEqualTo(204);

        Received r = inbox.poll(10, TimeUnit.SECONDS);
        assertThat(r).isNotNull();
        assertThat(r.header("X-BetterCRM-Event")).isEqualTo("webhook.test");
        assertSigned(r, secret);
        JsonNode env = json.readTree(r.body());
        assertThat(env.path("event").asText()).isEqualTo("webhook.test");
        assertThat(env.path("organizationId").asLong()).isEqualTo(admin.getOrganizationId());

        // the secret is never shown again, and is stored encrypted
        String listing = getAs(admin, "/api/integrations/webhooks").andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(secret);
    }

    @Test
    void crmChangesAreDeliveredOnlyForSubscribedEvents() throws Exception {
        User admin = user(newOrg(), Role.SUPER_ADMIN);
        JsonNode hook = body(postAs(admin, "/api/integrations/webhooks",
                Map.of("name", "users only", "url", url(), "events", List.of("user.created"))).andExpect(status().isCreated()));
        String secret = hook.path("secret").asText();

        postAs(admin, "/api/departments", Map.of("name", "Not published")).andExpect(status().isCreated());
        postAs(admin, "/api/users", Map.of("name", "Webhook Person", "email", "wh-" + System.nanoTime() + "@test.example",
                "password", "Granite-Lake-4417", "role", "EXECUTIVE")).andExpect(status().isCreated());

        Received r = inbox.poll(15, TimeUnit.SECONDS);
        assertThat(r).isNotNull();
        assertThat(r.header("X-BetterCRM-Event")).isEqualTo("user.created");
        assertSigned(r, secret);
        JsonNode env = json.readTree(r.body());
        assertThat(env.path("data").path("name").asText()).isEqualTo("Webhook Person");
        assertThat(r.body()).doesNotContain("Granite-Lake-4417").doesNotContain("\"password\"");
        assertThat(inbox.poll(1, TimeUnit.SECONDS)).as("no other events").isNull();
    }

    @Test
    void webhooksAndKeysAreSuperAdminOnly() throws Exception {
        Long org = newOrg();
        for (Role r : new Role[]{Role.VP_SALES, Role.MANAGER, Role.EXECUTIVE, Role.SUPPORT}) {
            User u = user(org, r);
            getAs(u, "/api/integrations").andExpect(status().isForbidden());
            postAs(u, "/api/integrations/webhooks", Map.of("name", "x", "url", url(), "events", List.of("*")))
                    .andExpect(status().isForbidden());
            postAs(u, "/api/integrations/api-keys", Map.of("name", "x")).andExpect(status().isForbidden());
        }
        // private/loopback targets are refused unless explicitly allowed (allowed in the test profile)
        User admin = user(org, Role.SUPER_ADMIN);
        postAs(admin, "/api/integrations/webhooks", Map.of("name", "bad", "url", "ftp://example.com/x", "events", List.of("*")))
                .andExpect(status().isBadRequest());
    }
}
