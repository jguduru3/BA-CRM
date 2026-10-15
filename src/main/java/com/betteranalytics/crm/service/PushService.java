package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.DeviceToken;
import com.betteranalytics.crm.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Firebase Cloud Messaging over the HTTP v1 API (the legacy /fcm/send API is shut down).
 *
 *   POST https://fcm.googleapis.com/v1/projects/{projectId}/messages:send
 *   Authorization: Bearer <OAuth2 access token>
 *
 * The access token comes from a Google service account (env FCM_SERVICE_ACCOUNT_JSON,
 * raw JSON or base64 of it): we sign a JWT (RS256, java.security only — no SDK) and
 * exchange it at https://oauth2.googleapis.com/token, caching the token until shortly
 * before it expires. When FCM answers UNREGISTERED or INVALID_ARGUMENT for a device
 * token, that token is deleted. Without configuration every method is a no-op.
 *
 * Callers (NotificationService) invoke this after commit on a background thread;
 * every HTTP call has a 5s connect / 10s request timeout. Tokens are never logged.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
    static final String SEND_URL = "https://fcm.googleapis.com/v1/projects/%s/messages:send";
    private static final Set<String> DEAD_TOKEN_CODES = Set.of("UNREGISTERED", "INVALID_ARGUMENT");

    /** Minimal HTTP seam so the token-cleanup logic is unit-testable. */
    public interface Transport {
        Response post(String url, Map<String, String> headers, byte[] body) throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {}

    public record ServiceAccount(String projectId, String clientEmail, PrivateKey privateKey, String tokenUri) {}

    public enum Result { SENT, TOKEN_DELETED, FAILED }

    private final DeviceTokenRepository tokenRepo;
    private final ObjectMapper objectMapper;
    private final ServiceAccount account;
    private final Transport transport;
    private final Clock clock;

    private String cachedToken;
    private Instant cachedTokenExpiry = Instant.EPOCH;

    @Autowired
    public PushService(DeviceTokenRepository tokenRepo, ObjectMapper objectMapper,
                       @Value("${app.fcm.service-account-json:}") String serviceAccountJson,
                       @Value("${app.fcm.server-key:}") String legacyServerKey) {
        this(tokenRepo, objectMapper, parseServiceAccount(serviceAccountJson, objectMapper), jdkTransport(), Clock.systemUTC());
        if (legacyServerKey != null && !legacyServerKey.isBlank()) {
            log.warn("FCM_SERVER_KEY is set but the FCM legacy API is shut down — it is ignored. "
                    + "Configure FCM_SERVICE_ACCOUNT_JSON for the HTTP v1 API instead.");
        }
        log.info("Push notifications (FCM HTTP v1): {}", account != null ? "enabled for project " + account.projectId() : "disabled");
    }

    PushService(DeviceTokenRepository tokenRepo, ObjectMapper objectMapper, ServiceAccount account,
                Transport transport, Clock clock) {
        this.tokenRepo = tokenRepo;
        this.objectMapper = objectMapper;
        this.account = account;
        this.transport = transport;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return account != null;
    }

    /** Pushes to every active device of the user. Never throws. */
    public void sendToUser(Long userId, String title, String body, Map<String, String> data) {
        if (!isEnabled() || userId == null) return;
        List<DeviceToken> tokens;
        try {
            tokens = tokenRepo.findByUserId(userId);
        } catch (Exception e) {
            log.warn("Push skipped for userId={}: could not load device tokens ({})", userId, e.getClass().getSimpleName());
            return;
        }
        for (DeviceToken dt : tokens) {
            if (!dt.isActive()) continue;
            sendToDevice(dt, title, body, data);
        }
    }

    /** One device. Dead tokens (UNREGISTERED / INVALID_ARGUMENT) are deleted. */
    public Result sendToDevice(DeviceToken dt, String title, String body, Map<String, String> data) {
        if (!isEnabled()) return Result.FAILED;
        try {
            byte[] payload = objectMapper.writeValueAsBytes(message(dt.getToken(), title, body, data));
            Response r = postMessage(payload, false);
            if (r.status() == 401) {            // token revoked/expired early — refresh once
                r = postMessage(payload, true);
            }
            if (r.status() >= 200 && r.status() < 300) return Result.SENT;
            String code = errorCode(r.body());
            if (code != null && DEAD_TOKEN_CODES.contains(code)) {
                tokenRepo.deleteById(dt.getId());
                log.info("Deleted FCM device token id={} (FCM: {})", dt.getId(), code);
                return Result.TOKEN_DELETED;
            }
            log.warn("FCM push failed for deviceTokenId={}: HTTP {} {}", dt.getId(), r.status(), code != null ? code : "");
            return Result.FAILED;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("FCM push failed for deviceTokenId={}: {}", dt.getId(), e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    private Response postMessage(byte[] payload, boolean forceNewToken) throws IOException, InterruptedException {
        String accessToken = accessToken(forceNewToken);
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + accessToken,
                "Content-Type", "application/json; charset=UTF-8");
        return transport.post(String.format(SEND_URL, account.projectId()), headers, payload);
    }

    private Map<String, Object> message(String token, String title, String body, Map<String, String> data) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", title != null ? truncate(title, 200) : "");
        notification.put("body", body != null ? truncate(body, 500) : "");
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("token", token);
        msg.put("notification", notification);
        if (data != null && !data.isEmpty()) {
            Map<String, String> clean = new LinkedHashMap<>();
            data.forEach((k, v) -> { if (k != null && v != null) clean.put(k, truncate(v, 500)); });
            msg.put("data", clean);
        }
        return Map.of("message", msg);
    }

    /** FCM error code: details[].errorCode (FcmError) first, then error.status. */
    String errorCode(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode err = objectMapper.readTree(body).path("error");
            for (JsonNode d : err.path("details")) {
                String c = d.path("errorCode").asText(null);
                if (c != null && !c.isBlank()) return c;
            }
            String status = err.path("status").asText(null);
            return status == null || status.isBlank() ? null : status;
        } catch (Exception e) {
            return null;
        }
    }

    // ── OAuth2: service-account JWT → access token (cached) ─────────────────────

    synchronized String accessToken(boolean forceNew) throws IOException, InterruptedException {
        Instant now = clock.instant();
        if (!forceNew && cachedToken != null && now.isBefore(cachedTokenExpiry)) return cachedToken;

        String assertion = signedJwt(now);
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        Response r = transport.post(account.tokenUri(),
                Map.of("Content-Type", "application/x-www-form-urlencoded"), form.getBytes(StandardCharsets.UTF_8));
        if (r.status() < 200 || r.status() >= 300) {
            throw new IOException("OAuth token exchange failed: HTTP " + r.status());
        }
        JsonNode json = objectMapper.readTree(r.body());
        String token = json.path("access_token").asText(null);
        if (token == null || token.isBlank()) throw new IOException("OAuth token exchange returned no access_token");
        long expiresIn = json.path("expires_in").asLong(3600);
        cachedToken = token;
        cachedTokenExpiry = now.plusSeconds(Math.max(60, expiresIn - 120));   // refresh 2 min early
        return token;
    }

    String signedJwt(Instant now) {
        try {
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", account.clientEmail());
            claims.put("scope", SCOPE);
            claims.put("aud", account.tokenUri());
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", now.plus(Duration.ofHours(1)).getEpochSecond());
            String payload = b64.encodeToString(objectMapper.writeValueAsBytes(claims));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(account.privateKey());
            sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + b64.encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the FCM service-account JWT", e);
        }
    }

    // ── Configuration ──────────────────────────────────────────────────────────

    /** Raw JSON or base64(JSON) of a Google service-account key file; blank/invalid → null (disabled). */
    static ServiceAccount parseServiceAccount(String raw, ObjectMapper om) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String json = raw.trim();
            if (!json.startsWith("{")) json = new String(Base64.getMimeDecoder().decode(json), StandardCharsets.UTF_8);
            JsonNode n = om.readTree(json);
            String projectId = n.path("project_id").asText(null);
            String email = n.path("client_email").asText(null);
            String pem = n.path("private_key").asText(null);
            String tokenUri = n.path("token_uri").asText(DEFAULT_TOKEN_URI);
            if (projectId == null || email == null || pem == null) {
                log.warn("FCM_SERVICE_ACCOUNT_JSON is missing project_id / client_email / private_key — push disabled");
                return null;
            }
            if (!tokenUri.startsWith("https://")) tokenUri = DEFAULT_TOKEN_URI;
            return new ServiceAccount(projectId, email, privateKey(pem), tokenUri);
        } catch (Exception e) {
            log.warn("FCM_SERVICE_ACCOUNT_JSON could not be parsed ({}) — push disabled", e.getClass().getSimpleName());
            return null;
        }
    }

    static PrivateKey privateKey(String pem) throws Exception {
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    private static Transport jdkTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (url, headers, body) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(b::header);
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(resp.statusCode(), resp.body());
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
