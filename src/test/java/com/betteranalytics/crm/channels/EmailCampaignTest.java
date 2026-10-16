package com.betteranalytics.crm.channels;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.service.EmailConsentService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Email campaigns: real SMTP dispatch (fake transport), at-most-once, opt-outs, pause/cancel, counters. */
class EmailCampaignTest extends ChannelTestSupport {

    @Autowired EmailConsentService emailConsentService;

    private long createEmailCampaign(User u, EmailAccount account) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Spring newsletter");
        body.put("type", "email");
        body.put("emailAccountId", account.getId());
        body.put("emailSubject", "Hi {{contact.firstName}}\r\nBcc: evil@x.example");
        body.put("emailBody", "Dear {{contact.name}},\nOur {{campaign.name}} is out. {{lead.stage}}");
        return idOf(postAs(u, "/api/campaigns", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("email"))
                .andExpect(jsonPath("$.emailAccountId").value(account.getId())));
    }

    @Test
    void launchSendsEachRecipientOnceWithUnsubscribeHeaderAndSkipsOptedOut() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        EmailAccount box = mailbox(org, admin.getId());
        long id = createEmailCampaign(admin, box);
        String a = uniqueEmail("asha"), b = uniqueEmail("bala"), out = uniqueEmail("opted");
        Lead linked = lead(org, admin.getId(), "Meera Nair", uniqueEmail("meera"), null);
        linked.setStage("Qualified");
        leadRepository.save(linked);
        emailConsentService.record(org, out, EmailConsent.OPTED_OUT, EmailConsentService.SOURCE_MANUAL, "asked", admin.getId(), null);

        JsonNode added = body(postAs(admin, "/api/campaigns/" + id + "/contacts", List.of(
                Map.of("email", a, "name", "Asha Rao"),
                Map.of("email", a.toUpperCase(Locale.ROOT)),          // same address, different case
                Map.of("email", b, "name", "Bala"),
                Map.of("email", out),
                Map.of("relatedType", "Lead", "relatedId", linked.getId()),   // email taken from the lead
                Map.of("email", "not-an-address"),
                Map.of("phone", "+919876543210")))                    // no email → invalid for an email campaign
                .andExpect(status().isOk()));
        assertThat(added.path("added").asInt()).isEqualTo(4);
        assertThat(added.path("duplicates").asInt()).isEqualTo(1);
        assertThat(added.path("invalid").asInt()).isEqualTo(2);
        assertThat(added.path("optedOut").asInt()).isEqualTo(1);

        postAs(admin, "/api/campaigns/" + id + "/launch", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sending"));
        await("campaign completed", () -> "completed".equals(campaignStatus(id)));

        assertThat(mail.recipients()).containsExactlyInAnyOrder(a, b, linked.getEmail());
        assertThat(statusByEmail(id)).containsEntry(a, "sent").containsEntry(b, "sent")
                .containsEntry(out, "skipped_opted_out").containsEntry(linked.getEmail(), "sent");

        MimeMessage toAsha = mail.sent.stream().filter(m -> a.equals(FakeMailSender.to(m))).findFirst().orElseThrow();
        assertThat(toAsha.getSubject()).isEqualTo("Hi Asha Bcc: evil@x.example");     // no header injection
        assertThat(toAsha.getHeader("Bcc")).isNull();
        assertThat((String) toAsha.getContent()).startsWith("Dear Asha Rao,\nOur Spring newsletter is out.")
                .contains("To stop these emails");
        assertThat(toAsha.getHeader("List-Unsubscribe")[0]).contains("mailto:" + box.getEmailAddress());
        assertThat(toAsha.getHeader("Precedence")[0]).isEqualTo("bulk");
        assertThat(toAsha.getFrom()[0].toString()).contains(box.getEmailAddress());
        MimeMessage toMeera = mail.sent.stream().filter(m -> linked.getEmail().equals(FakeMailSender.to(m))).findFirst().orElseThrow();
        assertThat(toMeera.getSubject()).isEqualTo("Hi Meera Bcc: evil@x.example");
        assertThat((String) toMeera.getContent()).contains("is out. Qualified");      // lead.* variables

        JsonNode progress = body(getAs(admin, "/api/campaigns/" + id + "/progress").andExpect(status().isOk()));
        assertThat(progress.path("sentCount").asInt()).isEqualTo(3);
        assertThat(progress.path("failedCount").asInt()).isZero();
        assertThat(progress.path("counts").path("skipped_opted_out").asInt()).isEqualTo(1);
        assertThat(progress.path("percent").asDouble()).isEqualTo(100.0);
        // message ids are recorded per recipient
        assertThat(recipientRepository.findByCampaignId(id)).filteredOn(cc -> "sent".equals(cc.getStatus()))
                .allMatch(cc -> cc.getWaMessageId() != null && cc.getWaMessageId().contains("@"));
    }

    @Test
    void concurrentTicksSendEveryRecipientExactlyOnce() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        EmailAccount box = mailbox(org, admin.getId());
        Campaign c = sendingCampaign(org, admin.getId(), "email", box);
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < 120; i++) emails.add(emailRecipient(c, uniqueEmail("r" + i)).getEmailNormalized());

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            fs.add(pool.submit(() -> {
                for (int k = 0; k < 5; k++) {
                    dispatcher.runDue();
                    dispatcher.dispatch(c.getId());
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        await("campaign completed", () -> "completed".equals(campaignStatus(c.getId())));

        assertThat(mail.recipients()).hasSize(120).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(emails);
        assertThat(campaignRepository.findById(c.getId()).orElseThrow().getSentCount()).isEqualTo(120);
    }

    @Test
    void pauseStopsSendingAndResumeFinishesWithoutDuplicates() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        EmailAccount box = mailbox(org, admin.getId());
        Campaign c = sendingCampaign(org, admin.getId(), "email", box);
        for (int i = 0; i < 30; i++) emailRecipient(c, uniqueEmail("p" + i));
        AtomicInteger n = new AtomicInteger();
        mail.onSend = m -> {
            if (n.incrementAndGet() == 3) {
                Campaign fresh = campaignRepository.findById(c.getId()).orElseThrow();
                fresh.setStatus("paused");
                campaignRepository.save(fresh);
            }
        };

        dispatcher.dispatch(c.getId());   // synchronous run; sees the pause at the next status check
        int sentWhilePausing = mail.sent.size();
        assertThat(sentWhilePausing).isBetween(3, 10);
        assertThat(campaignStatus(c.getId())).isEqualTo("paused");
        assertThat(recipientRepository.countByCampaignIdAndStatus(c.getId(), "sent")).isEqualTo(sentWhilePausing);
        assertThat(recipientRepository.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(30 - sentWhilePausing);
        assertThat(recipientRepository.countByCampaignIdAndStatus(c.getId(), "sending")).isZero();
        dispatcher.dispatch(c.getId());   // paused: nothing happens
        assertThat(mail.sent).hasSize(sentWhilePausing);

        mail.onSend = m -> {};
        postAs(admin, "/api/campaigns/" + c.getId() + "/resume", Map.of()).andExpect(status().isOk());
        await("campaign completed", () -> "completed".equals(campaignStatus(c.getId())));
        assertThat(mail.recipients()).hasSize(30).doesNotHaveDuplicates();
        assertThat(campaignRepository.findById(c.getId()).orElseThrow().getSentCount()).isEqualTo(30);
    }

    @Test
    void cancelStopsSendingForGood() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        EmailAccount box = mailbox(org, admin.getId());
        Campaign c = sendingCampaign(org, admin.getId(), "email", box);
        for (int i = 0; i < 25; i++) emailRecipient(c, uniqueEmail("c" + i));
        mail.onSend = m -> {
            if (mail.sent.size() == 2) {
                Campaign fresh = campaignRepository.findById(c.getId()).orElseThrow();
                fresh.setStatus("cancelled");
                campaignRepository.save(fresh);
            }
        };
        dispatcher.dispatch(c.getId());
        int sent = mail.sent.size();
        assertThat(sent).isBetween(2, 10);
        dispatcher.runDue();
        dispatcher.dispatch(c.getId());
        assertThat(mail.sent).hasSize(sent);
        assertThat(campaignStatus(c.getId())).isEqualTo("cancelled");
        assertThat(recipientRepository.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(25 - sent);
    }

    @Test
    void permanentRejectionFailsRecipientAndLoginFailureKeepsThemPendingThenPauses() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        EmailAccount box = mailbox(org, admin.getId());
        Campaign c = sendingCampaign(org, admin.getId(), "email", box);
        String bad = uniqueEmail("bounce"), good = uniqueEmail("good");
        emailRecipient(c, bad);
        emailRecipient(c, good);
        CampaignContact noEmail = emailRecipient(c, null);
        mail.failFor.put(bad, new MailSendException("Failed messages",
                new SendFailedException("550 5.1.1 <" + bad + ">: user unknown")));

        dispatcher.dispatch(c.getId());
        assertThat(statusByEmail(c.getId())).containsEntry(bad, "failed").containsEntry(good, "sent");
        assertThat(recipientRepository.findById(noEmail.getId()).orElseThrow().getStatus()).isEqualTo("skipped");
        Campaign done = campaignRepository.findById(c.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo("completed");
        assertThat(done.getSentCount()).isEqualTo(1);
        assertThat(done.getFailedCount()).isEqualTo(1);
        assertThat(recipientRepository.findByCampaignId(c.getId())).filteredOn(cc -> bad.equals(cc.getEmailNormalized()))
                .first().extracting(CampaignContact::getFailedReason).asString().contains("undeliverable");

        // Mailbox login rejected: nothing was sent, so recipients stay pending; after 5 runs the campaign pauses.
        Campaign c2 = sendingCampaign(org, admin.getId(), "email", box);
        emailRecipient(c2, uniqueEmail("x"));
        emailRecipient(c2, uniqueEmail("y"));
        mail.failAll = new MailAuthenticationException("535 authentication failed");
        for (int i = 0; i < 4; i++) {
            dispatcher.dispatch(c2.getId());
            assertThat(campaignStatus(c2.getId())).isEqualTo("sending");
        }
        assertThat(recipientRepository.countByCampaignIdAndStatus(c2.getId(), "pending")).isEqualTo(2);
        dispatcher.dispatch(c2.getId());
        assertThat(campaignStatus(c2.getId())).isEqualTo("paused");
        assertThat(recipientRepository.countByCampaignIdAndStatus(c2.getId(), "pending")).isEqualTo(2);
        mail.failAll = null;
        postAs(admin, "/api/campaigns/" + c2.getId() + "/resume", Map.of()).andExpect(status().isOk());
        await("campaign completed", () -> "completed".equals(campaignStatus(c2.getId())));
        assertThat(recipientRepository.countByCampaignIdAndStatus(c2.getId(), "sent")).isEqualTo(2);
    }

    @Test
    void validationAndAudienceUseEmail() throws Exception {
        Long org = newOrg();
        User vp = user(org, Role.VP_SALES);
        User admin = user(org, Role.SUPER_ADMIN);
        long noContent = idOf(postAs(vp, "/api/campaigns", Map.of("name", "empty", "type", "email")).andExpect(status().isOk()));
        postAs(vp, "/api/campaigns/" + noContent + "/submit", Map.of()).andExpect(status().isBadRequest());
        postAs(vp, "/api/campaigns", Map.of("name", "x", "type", "email", "emailAccountId", 999999))
                .andExpect(status().isBadRequest());
        Long otherOrg = newOrg();
        EmailAccount foreign = mailbox(otherOrg, user(otherOrg, Role.SUPER_ADMIN).getId());
        postAs(vp, "/api/campaigns", Map.of("name", "x", "type", "email", "emailAccountId", foreign.getId()))
                .andExpect(status().isBadRequest());

        // No mailbox in the org → launch is a 409.
        long id = idOf(postAs(admin, "/api/campaigns", Map.of("name", "n", "type", "email",
                "emailSubject", "s", "emailBody", "b")).andExpect(status().isOk()));
        postAs(admin, "/api/campaigns/" + id + "/launch", Map.of()).andExpect(status().isConflict());

        lead(org, admin.getId(), "With Email", uniqueEmail("w"), null);
        lead(org, admin.getId(), "Phone Only", null, "+919811112222");
        JsonNode preview = body(postAs(admin, "/api/campaigns/" + id + "/audience/preview", Map.of())
                .andExpect(status().isOk()));
        assertThat(preview.path("channelField").asText()).isEqualTo("email");
        assertThat(preview.path("count").asInt()).isEqualTo(1);
        assertThat(preview.path("withoutEmail").asInt()).isEqualTo(1);
        assertThat(preview.path("recipients").get(0).path("validEmail").asBoolean()).isTrue();
        JsonNode applied = body(postAs(admin, "/api/campaigns/" + id + "/audience/apply", Map.of()).andExpect(status().isOk()));
        assertThat(applied.path("added").asInt()).isEqualTo(1);
        assertThat(recipientRepository.findByCampaignId(id)).singleElement()
                .satisfies(cc -> assertThat(cc.getEmailNormalized()).startsWith("w-"));

        JsonNode channels = body(getAs(admin, "/api/campaigns/channels").andExpect(status().isOk()));
        assertThat(channels.path("email").path("configured").asBoolean()).isFalse();
        assertThat(channels.path("sms").path("configured").asBoolean()).isFalse();
        mailbox(org, admin.getId());
        channels = body(getAs(admin, "/api/campaigns/channels").andExpect(status().isOk()));
        assertThat(channels.path("email").path("configured").asBoolean()).isTrue();
    }

    @Test
    void unsubscribeLinkOptsOutAndIsHonouredByCampaigns() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        String addr = uniqueEmail("leaving");
        String token = emailConsentService.token(org, addr, null);

        mvc.perform(get("/api/email/unsubscribe").param("t", token)).andExpect(status().isOk())
                .andExpect(content().string(containsString("<form method=\"post\"")));
        assertThat(emailConsentService.isOptedOut(org, addr)).isFalse();          // a GET never unsubscribes
        mvc.perform(get("/api/email/unsubscribe").param("t", token + "x")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/email/unsubscribe").param("t", token.replace('.', '!'))).andExpect(status().isBadRequest());

        mvc.perform(post("/api/email/unsubscribe").param("t", token)
                        .contentType("application/x-www-form-urlencoded").content("List-Unsubscribe=One-Click"))
                .andExpect(status().isOk());
        assertThat(emailConsentService.isOptedOut(org, addr)).isTrue();
        assertThat(emailConsentService.isOptedOut(newOrg(), addr)).isFalse();     // per organization
        JsonNode view = body(getAs(admin, "/api/email/consent?email=" + addr.toUpperCase(Locale.ROOT)).andExpect(status().isOk()));
        assertThat(view.path("status").asText()).isEqualTo("opted_out");
        assertThat(view.path("source").asText()).isEqualTo("unsubscribe_link");
        assertThat(view.path("history")).hasSize(1);

        EmailAccount box = mailbox(org, admin.getId());
        Campaign c = sendingCampaign(org, admin.getId(), "email", box);
        emailRecipient(c, addr);
        dispatcher.dispatch(c.getId());
        assertThat(mail.sent).isEmpty();
        assertThat(statusByEmail(c.getId())).containsEntry(addr, "skipped_opted_out");

        // Manual opt-in again needs a reason; then sends resume.
        postAs(admin, "/api/email/consent", Map.of("email", addr, "status", "opted_in")).andExpect(status().isBadRequest());
        postAs(admin, "/api/email/consent", Map.of("email", addr, "status", "opted_in", "reason", "re-subscribed by phone"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("opted_in"));
        postAs(user(org, Role.EXECUTIVE), "/api/email/consent",
                Map.of("email", addr, "status", "opted_out", "reason", "x")).andExpect(status().isForbidden());
    }
}
