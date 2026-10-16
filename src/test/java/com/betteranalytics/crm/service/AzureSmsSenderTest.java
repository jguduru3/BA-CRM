package com.betteranalytics.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Azure Communication Services SMS: HMAC request signing, request shape and response mapping. */
class AzureSmsSenderTest {

    static final String KEY_B64 = "dGVzdC1hY2Nlc3Mta2V5LTAxMjM0NTY3ODk=";   // "test-access-key-0123456789"
    static final String CONN = "endpoint=https://contoso.communication.azure.com/;accesskey=" + KEY_B64;

    @Test
    void signatureMatchesAFixedVectorComputedOutsideJava() {
        // Vector computed with Python's hmac/hashlib from the documented string-to-sign:
        //   "POST\n/sms?api-version=2021-03-07\nFri, 16 Oct 2026 10:00:00 GMT;contoso.communication.azure.com;<sha256>"
        byte[] body = ("{\"from\":\"+18005550100\",\"smsRecipients\":[{\"to\":\"+919876543210\"}],\"message\":\"Hello\","
                + "\"smsSendOptions\":{\"enableDeliveryReport\":false}}").getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = AzureSmsSender.signedHeaders("POST",
                URI.create("https://contoso.communication.azure.com/sms?api-version=2021-03-07"), body,
                "test-access-key-0123456789".getBytes(StandardCharsets.UTF_8), Instant.parse("2026-10-16T10:00:00Z"));
        assertThat(h).containsEntry("x-ms-date", "Fri, 16 Oct 2026 10:00:00 GMT")
                .containsEntry("x-ms-content-sha256", "MVcyckMvhdxz9NO6edrPKFfblhpMMbtEx6yGZlFb61I=")
                .containsEntry("Authorization", "HMAC-SHA256 SignedHeaders=x-ms-date;host;x-ms-content-sha256"
                        + "&Signature=S3XnQ50hhnrIbc1xA9q65RumeixntKstSsmFnXEM234=");
    }

    @Test
    void sendBuildsTheDocumentedRequestAndSignsIt() throws Exception {
        AtomicReference<URI> uri = new AtomicReference<>();
        AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        AtomicReference<byte[]> sentBody = new AtomicReference<>();
        AzureSmsSender sender = new AzureSmsSender(CONN, "+18005550100", (u, h, b) -> {
            uri.set(u);
            headers.set(h);
            sentBody.set(b);
            return new AzureSmsSender.HttpResult(202, """
                    {"value":[{"to":"+919876543210","messageId":"Outgoing_2026_abc","httpStatusCode":202,
                      "repeatabilityResult":"accepted","successful":true,"errorMessage":null}]}""");
        });
        assertThat(sender.isConfigured()).isTrue();
        UUID key = UUID.randomUUID();
        SmsSender.Result r = sender.send("+919876543210", "Hello Ravi", key);

        assertThat(r.outcome()).isEqualTo(SmsSender.Outcome.SENT);
        assertThat(r.messageId()).isEqualTo("Outgoing_2026_abc");
        assertThat(uri.get().toString()).isEqualTo("https://contoso.communication.azure.com/sms?api-version=2021-03-07");
        JsonNode json = new ObjectMapper().readTree(sentBody.get());
        assertThat(json.path("from").asText()).isEqualTo("+18005550100");
        assertThat(json.path("smsRecipients").get(0).path("to").asText()).isEqualTo("+919876543210");
        assertThat(json.path("message").asText()).isEqualTo("Hello Ravi");
        assertThat(json.path("smsSendOptions").path("enableDeliveryReport").asBoolean(true)).isFalse();
        assertThat(headers.get()).containsEntry("repeatability-request-id", key.toString())
                .containsKey("repeatability-first-sent");

        // Recompute the signature independently from the captured headers.
        String date = headers.get().get("x-ms-date");
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(sentBody.get()));
        assertThat(headers.get().get("x-ms-content-sha256")).isEqualTo(hash);
        String toSign = "POST\n/sms?api-version=2021-03-07\n" + date + ";contoso.communication.azure.com;" + hash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY_B64), "HmacSHA256"));
        String sig = Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        assertThat(headers.get().get("Authorization"))
                .isEqualTo("HMAC-SHA256 SignedHeaders=x-ms-date;host;x-ms-content-sha256&Signature=" + sig);
    }

    @Test
    void perRecipientResultAndHttpStatusAreMapped() {
        assertThat(AzureSmsSender.interpret(new AzureSmsSender.HttpResult(202,
                "{\"value\":[{\"to\":\"+1\",\"httpStatusCode\":400,\"successful\":false,\"errorMessage\":\"Invalid To phone number format.\"}]}")).outcome())
                .isEqualTo(SmsSender.Outcome.FAILED_PERMANENT);
        assertThat(AzureSmsSender.interpret(new AzureSmsSender.HttpResult(202,
                "{\"value\":[{\"to\":\"+1\",\"httpStatusCode\":503,\"successful\":false}]}")).outcome())
                .isEqualTo(SmsSender.Outcome.NOT_SENT);
        assertThat(AzureSmsSender.interpret(new AzureSmsSender.HttpResult(401, "{}")).outcome()).isEqualTo(SmsSender.Outcome.NOT_SENT);
        assertThat(AzureSmsSender.interpret(new AzureSmsSender.HttpResult(429, "")).outcome()).isEqualTo(SmsSender.Outcome.NOT_SENT);
        assertThat(AzureSmsSender.interpret(new AzureSmsSender.HttpResult(500, "")).outcome()).isEqualTo(SmsSender.Outcome.NOT_SENT);
        assertThat(AzureSmsSender.interpret(new AzureSmsSender.HttpResult(400, "{\"error\":{}}")).outcome())
                .isEqualTo(SmsSender.Outcome.FAILED_PERMANENT);
        assertThat(AzureSmsSender.interpret(new AzureSmsSender.HttpResult(202, "not json")).outcome())
                .isEqualTo(SmsSender.Outcome.UNKNOWN);
    }

    @Test
    void disabledCleanlyWhenUnsetOrInvalidAndValidatesInput() {
        assertThat(new AzureSmsSender("", "", null).isConfigured()).isFalse();
        assertThat(new AzureSmsSender(CONN, "", null).isConfigured()).isFalse();
        assertThat(new AzureSmsSender(CONN, "18005550100", null).isConfigured()).isFalse();          // not E.164
        assertThat(new AzureSmsSender("endpoint=http://x.example/;accesskey=" + KEY_B64, "+18005550100", null).isConfigured()).isFalse();
        assertThat(new AzureSmsSender("endpoint=https://x.example/", "+18005550100", null).isConfigured()).isFalse();
        AzureSmsSender off = new AzureSmsSender("", "", null);
        assertThat(off.send("+919876543210", "x", null).outcome()).isEqualTo(SmsSender.Outcome.NOT_SENT);

        AzureSmsSender on = new AzureSmsSender(CONN, "+18005550100", (u, h, b) -> { throw new ConnectException("refused"); });
        assertThat(on.send("+919876543210", "x", null).outcome()).isEqualTo(SmsSender.Outcome.NOT_SENT);
        assertThat(on.send("12345", "x", null).outcome()).isEqualTo(SmsSender.Outcome.FAILED_PERMANENT);
        assertThat(on.send("+919876543210", " ", null).outcome()).isEqualTo(SmsSender.Outcome.FAILED_PERMANENT);
        assertThat(on.fromNumber()).isEqualTo("+18005550100");
    }
}
