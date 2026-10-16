package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.DeviceToken;
import com.betteranalytics.crm.repository.DeviceTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * Web push through <b>Azure Notification Hubs</b> (no Firebase / Google services).
 *
 * How it fits together:
 *  - The browser subscribes with the standard Push API using the hub's VAPID
 *    public key and sends its PushSubscription (endpoint + p256dh + auth) to
 *    POST /api/notifications/device-tokens.
 *  - We register it as a hub <i>installation</i> (platform "browser") tagged
 *    {@code user:<id>}; the hub stores the keys and encrypts each payload.
 *  - A notification is sent once per user to the tag — the hub fans out to all
 *    of that user's browsers and drops endpoints the push service reports as gone.
 *
 * Configuration: AZURE_NOTIFICATION_HUB_CONNECTION_STRING (a DefaultFullSharedAccessSignature
 * connection string) + AZURE_NOTIFICATION_HUB_NAME, and WEBPUSH_VAPID_PUBLIC_KEY (the
 * public half of the VAPID key pair configured on the hub under "Browser (Web Push)").
 * Without them every method is a no-op. Auth is a SAS token signed with the JDK
 * (HMAC-SHA256) — no SDK. Calls have 5 s connect / 10 s request timeouts, never throw
 * to callers, and never log subscription endpoints.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    static final String API_VERSION = "2020-06";
    private static final long SAS_TTL_SECONDS = 3600;

    /** Minimal HTTP seam so the hub calls are unit-testable without a network. */
    public interface Transport {
        Response send(String method, String url, Map<String, String> headers, byte[] body)
                throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {}

    /** Parsed hub connection settings. */
    public record HubConfig(String namespaceHost, String hubName, String keyName, String key) {
        String baseUrl() {
            return "https://" + namespaceHost + "/" + hubName;
        }
    }

    public enum Result { SENT, FAILED, DISABLED }

    private final DeviceTokenRepository tokenRepo;
    private final ObjectMapper objectMapper;
    private final HubConfig hub;
    private final String vapidPublicKey;
    private final Transport transport;
    private final Clock clock;

    @Autowired
    public PushService(DeviceTokenRepository tokenRepo, ObjectMapper objectMapper,
                       @Value("${app.push.azure-hub.connection-string:}") String connectionString,
                       @Value("${app.push.azure-hub.name:}") String hubName,
                       @Value("${app.push.vapid-public-key:}") String vapidPublicKey) {
        this(tokenRepo, objectMapper, parseConnectionString(connectionString, hubName),
                vapidPublicKey, jdkTransport(), Clock.systemUTC());
        log.info("Push notifications (Azure Notification Hubs): {}",
                isEnabled() ? "enabled for hub " + hub.hubName() : "disabled");
    }

    PushService(DeviceTokenRepository tokenRepo, ObjectMapper objectMapper, HubConfig hub,
                String vapidPublicKey, Transport transport, Clock clock) {
        this.tokenRepo = tokenRepo;
        this.objectMapper = objectMapper;
        this.hub = hub;
        this.vapidPublicKey = vapidPublicKey == null ? "" : vapidPublicKey.trim();
        this.transport = transport;
        this.clock = clock;
    }

    /** Push works only when the hub is configured and the browser can get the VAPID key. */
    public boolean isEnabled() {
        return hub != null && !vapidPublicKey.isEmpty();
    }

    public String vapidPublicKey() {
        return isEnabled() ? vapidPublicKey : null;
    }

    /** Stable installation id for a browser subscription (never exposes the endpoint itself). */
    public static String installationIdFor(String endpoint) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8));
            return "web-" + HexFormat.of().formatHex(d, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String userTag(Long userId) {
        return "user:" + userId;
    }

    /** Creates or replaces the hub installation for a browser subscription. */
    public Result upsertBrowserInstallation(String installationId, Long userId,
                                            String endpoint, String p256dh, String auth) {
        if (!isEnabled()) return Result.DISABLED;
        try {
            Map<String, Object> pushChannel = new LinkedHashMap<>();
            pushChannel.put("endpoint", endpoint);
            pushChannel.put("p256dh", p256dh);
            pushChannel.put("auth", auth);
            Map<String, Object> installation = new LinkedHashMap<>();
            installation.put("installationId", installationId);
            installation.put("platform", "browser");
            installation.put("pushChannel", pushChannel);
            installation.put("tags", List.of(userTag(userId)));
            byte[] body = objectMapper.writeValueAsBytes(installation);

            String url = hub.baseUrl() + "/installations/" + enc(installationId) + "?api-version=" + API_VERSION;
            Response r = transport.send("PUT", url, headers("application/json", null), body);
            if (r.status() >= 200 && r.status() < 300) return Result.SENT;
            log.warn("Notification Hub rejected installation (HTTP {})", r.status());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("Notification Hub installation failed: {}", e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    public Result deleteInstallation(String installationId) {
        if (!isEnabled() || installationId == null) return Result.DISABLED;
        try {
            String url = hub.baseUrl() + "/installations/" + enc(installationId) + "?api-version=" + API_VERSION;
            Response r = transport.send("DELETE", url, headers(null, null), null);
            // 404 = already gone; both are fine.
            return (r.status() >= 200 && r.status() < 300) || r.status() == 404 ? Result.SENT : Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            return Result.FAILED;
        }
    }

    /**
     * Sends one notification to every browser of the user (hub tag fan-out). Never throws.
     * The JSON payload is what the service worker's "push" handler receives.
     */
    public Result sendToUser(Long userId, String title, String body, Map<String, String> data) {
        if (!isEnabled() || userId == null) return Result.DISABLED;
        try {
            if (!tokenRepo.existsByUserIdAndActiveTrue(userId)) return Result.DISABLED;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title == null ? "Better CRM" : title);
            payload.put("body", body == null ? "" : body);
            payload.put("data", data == null ? Map.of() : data);
            byte[] bytes = objectMapper.writeValueAsBytes(payload);

            Map<String, String> h = headers("application/json;charset=utf-8", userTag(userId));
            h.put("ServiceBusNotification-Format", "browser");
            Response r = transport.send("POST", hub.baseUrl() + "/messages/?api-version=" + API_VERSION, h, bytes);
            if (r.status() >= 200 && r.status() < 300) return Result.SENT;
            log.warn("Notification Hub send failed for userId={} (HTTP {})", userId, r.status());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("Notification Hub send failed for userId={}: {}", userId, e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    // ── SAS auth ─────────────────────────────────────────────────────────────

    private Map<String, String> headers(String contentType, String tags) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", sasToken());
        if (contentType != null) h.put("Content-Type", contentType);
        if (tags != null) h.put("ServiceBusNotification-Tags", tags);
        return h;
    }

    /** SharedAccessSignature sr=<uri>&sig=<HMAC-SHA256(key, uri + "\n" + expiry)>&se=<expiry>&skn=<keyName>. */
    String sasToken() {
        String resource = enc(hub.baseUrl().toLowerCase(Locale.ROOT));
        long expiry = clock.instant().getEpochSecond() + SAS_TTL_SECONDS;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hub.key().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getEncoder().encodeToString(
                    mac.doFinal((resource + "\n" + expiry).getBytes(StandardCharsets.UTF_8)));
            return "SharedAccessSignature sr=" + resource + "&sig=" + enc(sig) + "&se=" + expiry + "&skn=" + hub.keyName();
        } catch (Exception e) {
            throw new IllegalStateException("could not sign Notification Hub SAS token", e);
        }
    }

    /** Parses "Endpoint=sb://ns.servicebus.windows.net/;SharedAccessKeyName=...;SharedAccessKey=...". */
    static HubConfig parseConnectionString(String cs, String hubName) {
        if (cs == null || cs.isBlank() || hubName == null || hubName.isBlank()) return null;
        Map<String, String> parts = new HashMap<>();
        for (String seg : cs.trim().split(";")) {
            int i = seg.indexOf('=');
            if (i > 0) parts.put(seg.substring(0, i).trim().toLowerCase(Locale.ROOT), seg.substring(i + 1).trim());
        }
        String endpoint = parts.get("endpoint");
        String keyName = parts.get("sharedaccesskeyname");
        String key = parts.get("sharedaccesskey");
        if (endpoint == null || keyName == null || key == null) {
            log.warn("AZURE_NOTIFICATION_HUB_CONNECTION_STRING is malformed — push disabled");
            return null;
        }
        String host = URI.create(endpoint.replaceFirst("^sb://", "https://")).getHost();
        if (host == null) {
            log.warn("AZURE_NOTIFICATION_HUB_CONNECTION_STRING has no endpoint host — push disabled");
            return null;
        }
        return new HubConfig(host, hubName.trim(), keyName, key);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Transport jdkTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (method, url, headers, body) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
            headers.forEach(b::header);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        };
    }
}
