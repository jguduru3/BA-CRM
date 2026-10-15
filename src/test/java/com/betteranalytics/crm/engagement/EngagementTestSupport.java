package com.betteranalytics.crm.engagement;

import com.betteranalytics.crm.entity.Lead;
import com.betteranalytics.crm.entity.WhatsAppAccount;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.repository.WhatsAppAccountRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared helpers for the engagement-domain HTTP tests (campaigns, WhatsApp, automations). */
abstract class EngagementTestSupport extends IntegrationTest {

    /** Same value as app.whatsapp.app-secret in application-test.yml. */
    static final String WA_APP_SECRET = "test-whatsapp-app-secret";

    @Autowired protected LeadRepository leadRepository;
    @Autowired protected WhatsAppAccountRepository waAccountRepository;

    protected Lead lead(Long orgId, Long ownerId, String phone, String industry) {
        Lead l = new Lead();
        l.setOrganizationId(orgId);
        l.setName("Lead " + UUID.randomUUID().toString().substring(0, 6));
        l.setPhone(phone);
        l.setOwnerId(ownerId);
        l.setIndustry(industry);
        l.setCreatedAt(Instant.now());
        l.setUpdatedAt(Instant.now());
        return leadRepository.save(l);
    }

    /**
     * A sender account without an access token: sends fail fast with
     * "no usable access token" and never touch the network.
     */
    protected WhatsAppAccount offlineAccount(Long orgId) {
        WhatsAppAccount a = new WhatsAppAccount();
        a.setOrganizationId(orgId);
        a.setPhoneNumberId("pn" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        a.setDisplayPhoneNumber("+1555" + (1000000 + (int) (Math.random() * 8999999)));
        a.setActive(true);
        a.setCreatedAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        return waAccountRepository.save(a);
    }

    protected static String uniquePhone() {
        return "+9198" + String.format("%08d", (long) (Math.random() * 100_000_000L));
    }

    protected static String hubSignature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WA_APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    protected ResultActions postWebhook(String body, String signature) throws Exception {
        var req = post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) req.header("X-Hub-Signature-256", signature);
        return mvc.perform(req);
    }

    protected static String inboundPayload(String phoneNumberId, String from, String waId, String text) {
        return """
               {"object":"whatsapp_business_account","entry":[{"id":"x","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                 "messages":[{"from":"%s","id":"%s","type":"text","text":{"body":"%s"}}]}}]}]}
               """.formatted(phoneNumberId, from, waId, text);
    }

    protected static String statusPayload(String phoneNumberId, String waId, String status) {
        return """
               {"object":"whatsapp_business_account","entry":[{"id":"x","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                 "statuses":[{"id":"%s","status":"%s"}]}}]}]}
               """.formatted(phoneNumberId, waId, status);
    }

    /** Polls until the condition holds (background executor work), failing after ~15s. */
    protected static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
