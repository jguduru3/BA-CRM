package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.DeviceToken;
import com.betteranalytics.crm.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** FCM HTTP v1 client with a fake HTTP layer: OAuth token caching, payload, dead-token cleanup. */
class PushServiceTest {

    private final ObjectMapper om = new ObjectMapper();
    private DeviceTokenRepository repo;
    private KeyPair keys;
    private FakeTransport http;
    private PushService push;

    /** Records requests; answers the OAuth endpoint and then the queued FCM responses in order. */
    static class FakeTransport implements PushService.Transport {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final Deque<PushService.Response> fcm = new ArrayDeque<>();
        int tokenCalls = 0;

        @Override
        public PushService.Response post(String url, Map<String, String> h, byte[] body) {
            urls.add(url);
            headers.add(h);
            bodies.add(new String(body, StandardCharsets.UTF_8));
            if (url.contains("oauth2")) {
                tokenCalls++;
                return new PushService.Response(200, "{\"access_token\":\"ya29.token-" + tokenCalls + "\",\"expires_in\":3600}");
            }
            return fcm.isEmpty() ? new PushService.Response(200, "{\"name\":\"projects/p/messages/1\"}") : fcm.poll();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(DeviceTokenRepository.class);
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        http = new FakeTransport();
        PushService.ServiceAccount sa = new PushService.ServiceAccount(
                "demo-project", "push@demo-project.iam.gserviceaccount.com", keys.getPrivate(), PushService.DEFAULT_TOKEN_URI);
        push = new PushService(repo, om, sa, http, Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC));
    }

    private static DeviceToken token(long id, String value) {
        DeviceToken dt = new DeviceToken();
        dt.setId(id);
        dt.setUserId(7L);
        dt.setToken(value);
        dt.setActive(true);
        return dt;
    }

    private static PushService.Response fcmError(int status, String grpcStatus, String errorCode) {
        String details = errorCode == null ? "[]"
                : "[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"" + errorCode + "\"}]";
        return new PushService.Response(status,
                "{\"error\":{\"code\":" + status + ",\"message\":\"x\",\"status\":\"" + grpcStatus + "\",\"details\":" + details + "}}");
    }

    @Test
    void sendsV1MessageWithCachedOAuthToken() throws Exception {
        when(repo.findByUserId(7L)).thenReturn(List.of(token(1, "device-a"), token(2, "device-b")));
        push.sendToUser(7L, "Lead assigned", "Acme Corp", Map.of("type", "lead_assigned", "relatedId", "42"));

        assertThat(http.tokenCalls).isEqualTo(1);                       // one OAuth exchange for both devices
        assertThat(http.urls).filteredOn(u -> u.contains("fcm.googleapis.com")).hasSize(2)
                .allMatch(u -> u.equals("https://fcm.googleapis.com/v1/projects/demo-project/messages:send"));
        int sendIdx = http.urls.indexOf("https://fcm.googleapis.com/v1/projects/demo-project/messages:send");
        assertThat(http.headers.get(sendIdx).get("Authorization")).isEqualTo("Bearer ya29.token-1");
        JsonNode msg = om.readTree(http.bodies.get(sendIdx)).path("message");
        assertThat(msg.path("token").asText()).isEqualTo("device-a");
        assertThat(msg.path("notification").path("title").asText()).isEqualTo("Lead assigned");
        assertThat(msg.path("data").path("relatedId").asText()).isEqualTo("42");
        verify(repo, never()).deleteById(anyLong());

        // The OAuth assertion is an RS256 JWT signed with the service-account key.
        String form = http.bodies.get(0);
        assertThat(form).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
        String jwt = java.net.URLDecoder.decode(form.substring(form.indexOf("assertion=") + 10), StandardCharsets.UTF_8);
        String[] parts = jwt.split("\\.");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        JsonNode claims = om.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("scope").asText()).isEqualTo(PushService.SCOPE);
        assertThat(claims.path("iss").asText()).isEqualTo("push@demo-project.iam.gserviceaccount.com");
    }

    @Test
    void unregisteredTokenIsDeleted() {
        http.fcm.add(fcmError(404, "NOT_FOUND", "UNREGISTERED"));
        assertThat(push.sendToDevice(token(11, "stale"), "t", "b", null)).isEqualTo(PushService.Result.TOKEN_DELETED);
        verify(repo).deleteById(11L);
    }

    @Test
    void invalidArgumentTokenIsDeleted() {
        http.fcm.add(fcmError(400, "INVALID_ARGUMENT", null));
        assertThat(push.sendToDevice(token(12, "garbage"), "t", "b", null)).isEqualTo(PushService.Result.TOKEN_DELETED);
        verify(repo).deleteById(12L);
    }

    @Test
    void transientErrorsKeepTheToken() {
        http.fcm.add(fcmError(503, "UNAVAILABLE", "UNAVAILABLE"));
        http.fcm.add(fcmError(429, "RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED"));
        assertThat(push.sendToDevice(token(13, "ok"), "t", "b", null)).isEqualTo(PushService.Result.FAILED);
        assertThat(push.sendToDevice(token(13, "ok"), "t", "b", null)).isEqualTo(PushService.Result.FAILED);
        verify(repo, never()).deleteById(anyLong());
    }

    @Test
    void expiredAccessTokenIsRefreshedOnceOn401() {
        http.fcm.add(new PushService.Response(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}"));
        assertThat(push.sendToDevice(token(14, "ok"), "t", "b", null)).isEqualTo(PushService.Result.SENT);
        assertThat(http.tokenCalls).isEqualTo(2);
        verify(repo, never()).deleteById(anyLong());
    }

    @Test
    void disabledWithoutConfigurationAndParsesBase64Json() throws Exception {
        PushService off = new PushService(repo, om, null, http, Clock.systemUTC());
        assertThat(off.isEnabled()).isFalse();
        off.sendToUser(7L, "t", "b", Map.of());
        assertThat(http.urls).isEmpty();
        verifyNoInteractions(repo);

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        String json = om.writeValueAsString(Map.of("type", "service_account", "project_id", "p1",
                "client_email", "a@p1.iam.gserviceaccount.com", "private_key", pem));
        assertThat(PushService.parseServiceAccount(json, om).projectId()).isEqualTo("p1");
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertThat(PushService.parseServiceAccount(b64, om).clientEmail()).isEqualTo("a@p1.iam.gserviceaccount.com");
        assertThat(PushService.parseServiceAccount("{not json", om)).isNull();
        assertThat(PushService.parseServiceAccount("", om)).isNull();
    }
}
