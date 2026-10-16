package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sequence tests replace only the outbound edge ({@link SequenceChannelSender})
 * with a mock; scheduling, claims, permissions, webhooks and consent are real.
 * The scheduler is idle in tests (app.sequences.scheduler-initial-delay-ms), so
 * each test drives {@link SequenceEngine#tick()} itself.
 */
abstract class SequenceTestSupport extends IntegrationTest {

    static final String WA_APP_SECRET = "test-whatsapp-app-secret";

    @MockBean protected SequenceChannelSender sender;
    @Autowired protected SequenceEngine engine;
    @Autowired protected SequenceEnrollmentRepository enrollmentRepository;
    @Autowired protected SequenceStepExecutionRepository executionRepository;
    @Autowired protected SequenceEnrollmentEventRepository eventRepository;
    @Autowired protected LeadRepository leadRepository;
    @Autowired protected ContactRepository contactRepository;
    @Autowired protected EmailAccountRepository emailAccountRepository;
    @Autowired protected WhatsAppAccountRepository waAccountRepository;
    @Autowired protected TaskRepository taskRepository;

    @BeforeEach
    void defaultSends() {
        when(sender.sendEmail(any(), any(), any(), any())).thenReturn(SequenceChannelSender.Result.sent("ok"));
        when(sender.sendWhatsAppTemplate(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SequenceChannelSender.Result(SequenceChannelSender.Outcome.SENT, "ok",
                        "wamid." + UUID.randomUUID(), null));
    }

    protected Lead lead(Long orgId, Long ownerId, String email, String phone) {
        Lead l = new Lead();
        l.setOrganizationId(orgId);
        l.setName("Asha " + UUID.randomUUID().toString().substring(0, 6));
        l.setEmail(email);
        l.setPhone(phone);
        l.setOwnerId(ownerId);
        l.setCreatedAt(Instant.now());
        l.setUpdatedAt(Instant.now());
        return leadRepository.save(l);
    }

    protected EmailAccount mailbox(Long orgId, Long userId) {
        EmailAccount a = new EmailAccount();
        a.setOrganizationId(orgId);
        a.setUserId(userId);
        a.setEmailAddress("rep-" + UUID.randomUUID().toString().substring(0, 6) + "@sender.example");
        a.setSmtpHost("smtp.example.com");
        a.setActive(true);
        a.setCreatedAt(Instant.now());
        return emailAccountRepository.save(a);
    }

    protected WhatsAppAccount waAccount(Long orgId) {
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
        return "+9197" + String.format("%08d", (long) (Math.random() * 100_000_000L));
    }

    protected static String uniqueEmail() {
        return "lead-" + UUID.randomUUID().toString().substring(0, 8) + "@client.example";
    }

    protected static Map<String, Object> email(int delayDays, String subject, String body) {
        return Map.of("channel", "EMAIL", "delayDays", delayDays, "subject", subject, "body", body);
    }

    protected static Map<String, Object> whatsapp(int delayDays) {
        return Map.of("channel", "WHATSAPP", "delayDays", delayDays, "waTemplateName", "follow_up",
                "waTemplateLanguage", "en_US", "waTemplateParams", List.of("{{record.firstName}}"));
    }

    protected static Map<String, Object> task(int delayDays, String title) {
        return Map.of("channel", "TASK", "delayDays", delayDays, "subject", title);
    }

    protected long createSequence(User manager, Object... steps) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Seq " + UUID.randomUUID().toString().substring(0, 6));
        body.put("steps", List.of(steps));
        return idOf(postAs(manager, "/api/sequences", body).andExpect(status().isCreated()));
    }

    protected long enroll(User actor, long sequenceId, String type, long recordId) throws Exception {
        return idOf(postAs(actor, "/api/sequences/" + sequenceId + "/enrollments",
                Map.of("relatedType", type, "relatedId", recordId)).andExpect(status().isCreated()));
    }

    /** Makes the enrollment's next step due now. */
    protected void makeDue(long enrollmentId) {
        SequenceEnrollment e = enrollmentRepository.findById(enrollmentId).orElseThrow();
        e.setNextRunAt(Instant.now().minusSeconds(1));
        enrollmentRepository.save(e);
    }

    protected SequenceEnrollment enrollment(long id) {
        return enrollmentRepository.findById(id).orElseThrow();
    }

    protected List<SequenceStepExecution> executions(long enrollmentId) {
        return executionRepository.findByEnrollmentIdOrderByStepIndexAsc(enrollmentId);
    }

    protected JsonNode detail(User u, long enrollmentId) throws Exception {
        return body(getAs(u, "/api/sequence-enrollments/" + enrollmentId).andExpect(status().isOk()));
    }

    // ── WhatsApp webhook helpers (same signing as app.whatsapp.app-secret in application-test.yml) ──

    protected ResultActions postWebhook(String body) throws Exception {
        return mvc.perform(post("/api/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Hub-Signature-256", hubSignature(body)));
    }

    protected static String hubSignature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WA_APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    protected static String inboundPayload(String phoneNumberId, String from, String text) {
        return """
               {"object":"whatsapp_business_account","entry":[{"id":"x","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                 "messages":[{"from":"%s","id":"wamid.in-%s","type":"text","text":{"body":"%s"}}]}}]}]}
               """.formatted(phoneNumberId, from, UUID.randomUUID(), text);
    }

    protected static String failedStatusPayload(String phoneNumberId, String waId) {
        return """
               {"object":"whatsapp_business_account","entry":[{"id":"x","changes":[{"field":"messages","value":{
                 "messaging_product":"whatsapp","metadata":{"phone_number_id":"%s"},
                 "statuses":[{"id":"%s","status":"failed","errors":[{"code":131026,"title":"Message undeliverable"}]}]}}]}]}
               """.formatted(phoneNumberId, waId);
    }
}
