package com.betteranalytics.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SMS through the Azure Communication Services REST API (no SDK):
 *   POST {endpoint}/sms?api-version=2021-03-07
 * authenticated with HMAC-SHA256 over
 *   "POST\n" + pathAndQuery + "\n" + x-ms-date + ";" + host + ";" + x-ms-content-sha256
 * keyed with the base64-decoded access key of the connection string
 *   endpoint=https://&lt;resource&gt;.communication.azure.com/;accesskey=&lt;base64&gt;
 *
 * Config: app.sms.connection-string (AZURE_COMMUNICATION_CONNECTION_STRING) and
 * app.sms.from (AZURE_SMS_FROM, E.164). Either blank → SMS disabled cleanly.
 * Timeouts: 5s connect, 15s per request. The per-recipient "successful" /
 * "httpStatusCode" in the 202 response is the result.
 */
@Component
public class AzureSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(AzureSmsSender.class);
    public static final String API_VERSION = "2021-03-07";
    static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** HTTP edge, replaceable in tests. */
    public interface Transport {
        HttpResult post(URI uri, Map<String, String> headers, byte[] body) throws IOException, InterruptedException;
    }

    public record HttpResult(int status, String body) {}

    record ConnectionString(URI endpoint, byte[] accessKey) {}

    private final ConnectionString connection;
    private final String from;
    private final Transport transport;

    @Autowired
    public AzureSmsSender(@Value("${app.sms.connection-string:}") String connectionString,
                          @Value("${app.sms.from:}") String from) {
        this(connectionString, from, null);
    }

    public AzureSmsSender(String connectionString, String from, Transport transport) {
        ConnectionString parsed = null;
        if (connectionString != null && !connectionString.isBlank()) {
            try {
                parsed = parseConnectionString(connectionString);
            } catch (IllegalArgumentException e) {
                log.error("app.sms.connection-string is invalid ({}); SMS is disabled", e.getMessage());
            }
        }
        String f = from == null ? "" : from.trim();
        if (parsed != null && !f.isEmpty() && !E164.matcher(f).matches()) {
            log.error("app.sms.from must be an E.164 number like +18005550100; SMS is disabled");
            parsed = null;
        }
        this.connection = f.isEmpty() ? null : parsed;
        this.from = this.connection == null ? null : f;
        this.transport = transport != null ? transport : httpTransport();
    }

    @Override
    public boolean isConfigured() {
        return connection != null;
    }

    @Override
    public String fromNumber() {
        return from;
    }

    @Override
    public Result send(String toE164, String message, UUID idempotencyKey) {
        if (!isConfigured()) return new Result(Outcome.NOT_SENT, null, "SMS is not configured");
        if (toE164 == null || !E164.matcher(toE164).matches())
            return new Result(Outcome.FAILED_PERMANENT, null, "Invalid phone number");
        if (message == null || message.isBlank())
            return new Result(Outcome.FAILED_PERMANENT, null, "Empty message");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from);
        payload.put("smsRecipients", List.of(Map.of("to", toE164)));
        payload.put("message", message);
        payload.put("smsSendOptions", Map.of("enableDeliveryReport", false));
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(payload);
        } catch (Exception e) {
            return new Result(Outcome.FAILED_PERMANENT, null, "Could not encode the message");
        }

        URI uri = connection.endpoint().resolve("/sms?api-version=" + API_VERSION);
        Map<String, String> headers = signedHeaders("POST", uri, body, connection.accessKey(), Instant.now());
        headers.put("Content-Type", "application/json");
        if (idempotencyKey != null) {
            headers.put("repeatability-request-id", idempotencyKey.toString());
            headers.put("repeatability-first-sent", RFC1123.format(Instant.now()));
        }

        HttpResult r;
        try {
            r = transport.post(uri, headers, body);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            return new Result(Outcome.NOT_SENT, null, "SMS gateway unreachable");
        } catch (java.net.http.HttpTimeoutException e) {
            return new Result(Outcome.UNKNOWN, null, "SMS gateway timed out (no confirmation)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.NOT_SENT, null, "Interrupted");
        } catch (IOException e) {
            return new Result(Outcome.UNKNOWN, null, "SMS gateway error (no confirmation)");
        }
        return interpret(r);
    }

    /** Maps the HTTP response (and the per-recipient entry of a 202) to an outcome. */
    static Result interpret(HttpResult r) {
        int status = r.status();
        if (status == 401 || status == 403) return new Result(Outcome.NOT_SENT, null, "SMS gateway rejected the credentials (HTTP " + status + ")");
        if (status == 429 || status >= 500) return new Result(Outcome.NOT_SENT, null, "SMS gateway busy (HTTP " + status + ")");
        JsonNode entry = null;
        try {
            JsonNode root = r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
            if (root != null && root.path("value").isArray() && !root.path("value").isEmpty()) entry = root.path("value").get(0);
        } catch (Exception ignored) {
            // fall through on the HTTP status
        }
        if (status >= 200 && status < 300 && entry != null) {
            boolean ok = entry.path("successful").asBoolean(false);
            int code = entry.path("httpStatusCode").asInt(status);
            String id = entry.path("messageId").isTextual() ? entry.path("messageId").asText() : null;
            if (ok && code >= 200 && code < 300) return new Result(Outcome.SENT, id, "Accepted");
            String err = entry.path("errorMessage").isTextual() ? entry.path("errorMessage").asText() : ("HTTP " + code);
            if (code == 429 || code >= 500) return new Result(Outcome.NOT_SENT, id, "SMS gateway busy: " + truncate(err));
            return new Result(Outcome.FAILED_PERMANENT, id, "Rejected: " + truncate(err));
        }
        if (status >= 200 && status < 300) return new Result(Outcome.UNKNOWN, null, "Unexpected SMS gateway response");
        return new Result(Outcome.FAILED_PERMANENT, null, "SMS gateway rejected the request (HTTP " + status + ")");
    }

    // ── Signing ──────────────────────────────────────────────────────────────

    /** x-ms-date, x-ms-content-sha256 and Authorization for one request. */
    static Map<String, String> signedHeaders(String method, URI uri, byte[] body, byte[] key, Instant now) {
        String date = RFC1123.format(now);
        String contentHash = Base64.getEncoder().encodeToString(sha256(body));
        String host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
        String signature = sign(method, pathAndQuery, date, host, contentHash, key);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-ms-date", date);
        h.put("x-ms-content-sha256", contentHash);
        h.put("Authorization", "HMAC-SHA256 SignedHeaders=x-ms-date;host;x-ms-content-sha256&Signature=" + signature);
        return h;
    }

    static String sign(String method, String pathAndQuery, String date, String host, String contentHash, byte[] key) {
        String toSign = method + "\n" + pathAndQuery + "\n" + date + ";" + host + ";" + contentHash;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    static ConnectionString parseConnectionString(String cs) {
        String endpoint = null;
        String key = null;
        for (String part : cs.trim().split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();
            if (name.equals("endpoint")) endpoint = value;
            else if (name.equals("accesskey")) key = value;
        }
        if (endpoint == null || key == null) throw new IllegalArgumentException("needs endpoint=…;accesskey=…");
        URI uri;
        try {
            uri = URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("endpoint is not a URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
            throw new IllegalArgumentException("endpoint must be an https URL");
        byte[] k;
        try {
            k = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("accesskey is not base64");
        }
        if (k.length == 0) throw new IllegalArgumentException("accesskey is empty");
        return new ConnectionString(uri, k);
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    private static Transport httpTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (uri, headers, body) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(b::header);
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String text = resp.body();
            if (text != null && text.length() > 10_000) text = text.substring(0, 10_000);
            return new HttpResult(resp.statusCode(), text);
        };
    }
}
