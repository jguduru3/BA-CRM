package com.betteranalytics.crm.service;

import com.betteranalytics.crm.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Azure Notification Hubs web push — exercised through a fake HTTP transport. */
class PushServiceTest {

    record Call(String method, String url, Map<String, String> headers, byte[] body) {}

    private final ObjectMapper json = new ObjectMapper();
    private final List<Call> calls = new ArrayList<>();
    private int nextStatus = 201;
    private DeviceTokenRepository repo;
    private PushService push;

    private static final String CS =
            "Endpoint=sb://crm-ns.servicebus.windows.net/;SharedAccessKeyName=DefaultFullSharedAccessSignature;SharedAccessKey=c2VjcmV0LWtleQ==";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(DeviceTokenRepository.class);
        when(repo.existsByUserIdAndActiveTrue(7L)).thenReturn(true);
        PushService.Transport t = (method, url, headers, body) -> {
            calls.add(new Call(method, url, headers, body));
            return new PushService.Response(nextStatus, "");
        };
        push = new PushService(repo, json, PushService.parseConnectionString(CS, "crm-hub"), "BPublicVapidKey", t, CLOCK);
    }

    @Test
    void parsesConnectionStringAndIsEnabled() {
        assertThat(push.isEnabled()).isTrue();
        assertThat(push.vapidPublicKey()).isEqualTo("BPublicVapidKey");
        PushService.HubConfig c = PushService.parseConnectionString(CS, "crm-hub");
        assertThat(c.namespaceHost()).isEqualTo("crm-ns.servicebus.windows.net");
        assertThat(c.keyName()).isEqualTo("DefaultFullSharedAccessSignature");
    }

    @Test
    void disabledWithoutConfigurationOrVapidKey() {
        PushService.Transport fail = (m, u, h, b) -> { throw new AssertionError("no HTTP when disabled"); };
        PushService none = new PushService(repo, json, null, "BKey", fail, CLOCK);
        PushService noKey = new PushService(repo, json, PushService.parseConnectionString(CS, "crm-hub"), "", fail, CLOCK);
        assertThat(none.isEnabled()).isFalse();
        assertThat(noKey.isEnabled()).isFalse();
        assertThat(none.sendToUser(7L, "t", "b", Map.of())).isEqualTo(PushService.Result.DISABLED);
        assertThat(noKey.upsertBrowserInstallation("web-x", 7L, "https://push.example/1", "p", "a"))
                .isEqualTo(PushService.Result.DISABLED);
        assertThat(PushService.parseConnectionString("garbage", "hub")).isNull();
        assertThat(PushService.parseConnectionString(CS, "")).isNull();
    }

    @Test
    void sasTokenIsSignedOverTheLowercasedEncodedHubUri() throws Exception {
        String sas = push.sasToken();
        assertThat(sas).startsWith("SharedAccessSignature sr=");
        Map<String, String> p = new HashMap<>();
        for (String kv : sas.substring("SharedAccessSignature ".length()).split("&")) {
            int i = kv.indexOf('=');
            p.put(kv.substring(0, i), kv.substring(i + 1));
        }
        String resource = p.get("sr");
        assertThat(URLDecoder.decode(resource, StandardCharsets.UTF_8))
                .isEqualTo("https://crm-ns.servicebus.windows.net/crm-hub");
        assertThat(p.get("skn")).isEqualTo("DefaultFullSharedAccessSignature");
        long se = Long.parseLong(p.get("se"));
        assertThat(se).isEqualTo(CLOCK.instant().getEpochSecond() + 3600);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("c2VjcmV0LWtleQ==".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal((resource + "\n" + se).getBytes(StandardCharsets.UTF_8)));
        assertThat(p.get("sig")).isEqualTo(URLEncoder.encode(expected, StandardCharsets.UTF_8));
    }

    @Test
    void registersBrowserInstallationTaggedWithTheUser() throws Exception {
        String id = PushService.installationIdFor("https://push.example/sub/abc");
        assertThat(id).startsWith("web-").hasSize(36).doesNotContain("push.example");

        assertThat(push.upsertBrowserInstallation(id, 7L, "https://push.example/sub/abc", "P256", "AUTH"))
                .isEqualTo(PushService.Result.SENT);
        Call c = calls.get(0);
        assertThat(c.method()).isEqualTo("PUT");
        assertThat(c.url()).isEqualTo("https://crm-ns.servicebus.windows.net/crm-hub/installations/" + id + "?api-version=2020-06");
        assertThat(c.headers().get("Authorization")).startsWith("SharedAccessSignature ");
        JsonNode body = json.readTree(c.body());
        assertThat(body.path("platform").asText()).isEqualTo("browser");
        assertThat(body.path("pushChannel").path("endpoint").asText()).isEqualTo("https://push.example/sub/abc");
        assertThat(body.path("pushChannel").path("p256dh").asText()).isEqualTo("P256");
        assertThat(body.path("pushChannel").path("auth").asText()).isEqualTo("AUTH");
        assertThat(body.path("tags").get(0).asText()).isEqualTo("user:7");
    }

    @Test
    void sendsOneTaggedBrowserNotificationPerUser() throws Exception {
        assertThat(push.sendToUser(7L, "Deal won", "Acme signed", Map.of("relatedType", "Opportunity", "relatedId", "5")))
                .isEqualTo(PushService.Result.SENT);
        assertThat(calls).hasSize(1);
        Call c = calls.get(0);
        assertThat(c.method()).isEqualTo("POST");
        assertThat(c.url()).isEqualTo("https://crm-ns.servicebus.windows.net/crm-hub/messages/?api-version=2020-06");
        assertThat(c.headers()).containsEntry("ServiceBusNotification-Format", "browser")
                .containsEntry("ServiceBusNotification-Tags", "user:7");
        JsonNode body = json.readTree(c.body());
        assertThat(body.path("title").asText()).isEqualTo("Deal won");
        assertThat(body.path("data").path("relatedId").asText()).isEqualTo("5");
    }

    @Test
    void usersWithoutDevicesAreSkippedAndFailuresNeverThrow() {
        assertThat(push.sendToUser(99L, "t", "b", Map.of())).isEqualTo(PushService.Result.DISABLED);
        assertThat(calls).isEmpty();

        nextStatus = 500;
        assertThat(push.sendToUser(7L, "t", "b", Map.of())).isEqualTo(PushService.Result.FAILED);

        PushService.Transport boom = (m, u, h, b) -> { throw new java.io.IOException("network down"); };
        PushService broken = new PushService(repo, json, PushService.parseConnectionString(CS, "crm-hub"), "BKey", boom, CLOCK);
        assertThat(broken.sendToUser(7L, "t", "b", Map.of())).isEqualTo(PushService.Result.FAILED);
    }

    @Test
    void deletingAMissingInstallationIsFine() {
        nextStatus = 404;
        assertThat(push.deleteInstallation("web-gone")).isEqualTo(PushService.Result.SENT);
        assertThat(calls.get(0).method()).isEqualTo("DELETE");
    }
}
