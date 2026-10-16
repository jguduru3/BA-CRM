package com.betteranalytics.crm.channels;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.ActivityRepository;
import com.betteranalytics.crm.sequences.*;
import com.betteranalytics.crm.service.EmailConsentService;
import com.betteranalytics.crm.service.InboxFetcher;
import com.betteranalytics.crm.service.InboxPoller;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Scheduled IMAP polling (fake mail store): sequence replies / bounces, unsubscribe replies, activity log. */
class InboxPollerTest extends ChannelTestSupport {

    @Autowired InboxPoller poller;
    @Autowired EmailConsentService emailConsentService;
    @Autowired SequenceEnrollmentRepository enrollmentRepository;
    @Autowired SequenceStepExecutionRepository executionRepository;
    @Autowired SequenceEngine engine;
    @Autowired ActivityRepository activityRepository;

    private static MimeMessage message(String from, String subject, String body) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setSubject(subject);
        m.setText(body);
        m.setSentDate(new Date(System.currentTimeMillis() + 1000));
        m.saveChanges();
        return m;
    }

    private long sequenceWithEmailStep(User owner, int delayDays) throws Exception {
        return idOf(postAs(owner, "/api/sequences", Map.of("name", "Follow-up " + UUID.randomUUID().toString().substring(0, 5),
                "steps", List.of(Map.of("channel", "EMAIL", "delayDays", delayDays,
                        "subject", "Checking in", "body", "Hi {{record.firstName}}"))))
                .andExpect(status().isCreated()));
    }

    private long enroll(User actor, long seq, String type, long id) throws Exception {
        return idOf(postAs(actor, "/api/sequences/" + seq + "/enrollments", Map.of("relatedType", type, "relatedId", id))
                .andExpect(status().isCreated()));
    }

    @Test
    void pollReadsOnlyNewMailAndFeedsSequencesConsentAndTimeline() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        EmailAccount box = mailbox(org, admin.getId());
        box.setPollInbox(true);
        box = emailAccountRepository.save(box);
        Lead replier = lead(org, admin.getId(), "Kiran Shah", uniqueEmail("kiran"), null);
        Lead bounced = lead(org, admin.getId(), "Bad Address", uniqueEmail("bounce"), null);
        Contact leaver = contact(org, admin.getId(), "Leela Das", uniqueEmail("leela"));
        long seq = sequenceWithEmailStep(admin, 3);
        long e1 = enroll(admin, seq, "Lead", replier.getId());
        long e2 = enroll(admin, seq, "Lead", bounced.getId());

        // First poll: baseline only.
        when(inboxFetcher.fetchNew(any(), isNull(), isNull(), anyInt()))
                .thenReturn(new InboxFetcher.Batch(7, 100, List.of()));
        assertThat(poller.poll(emailAccountRepository.findById(box.getId()).orElseThrow())).isZero();
        EmailAccount afterBaseline = emailAccountRepository.findById(box.getId()).orElseThrow();
        assertThat(afterBaseline.getLastPolledUid()).isEqualTo(100);
        assertThat(afterBaseline.getLastPolledUidValidity()).isEqualTo(7);
        assertThat(afterBaseline.getLastPolledAt()).isNotNull();

        MimeMessage ooo = message(leaver.getEmail(), "Out of office", "I am away");
        MimeMessage bounce = message("mailer-daemon@mx.example", "Undelivered Mail Returned to Sender", "failed");
        bounce.setHeader("X-Failed-Recipients", bounced.getEmail());
        List<InboxFetcher.Fetched> batch = List.of(
                new InboxFetcher.Fetched(101, message(replier.getEmail().toUpperCase(Locale.ROOT), "Re: Checking in",
                        "Yes, let's talk on Monday.\n\n> Hi Kiran")),
                new InboxFetcher.Fetched(102, ooo),
                new InboxFetcher.Fetched(103, bounce),
                new InboxFetcher.Fetched(104, message(leaver.getEmail(), "Re: Spring newsletter", "Unsubscribe\n\nthanks")),
                new InboxFetcher.Fetched(105, message(uniqueEmail("stranger"), "Hello", "who are you")),
                new InboxFetcher.Fetched(106, message(box.getEmailAddress(), "copy", "own message")));
        when(inboxFetcher.fetchNew(any(), eq(7L), eq(100L), anyInt())).thenReturn(new InboxFetcher.Batch(7, 106, batch));

        poller.pollAll();

        EmailAccount polled = emailAccountRepository.findById(box.getId()).orElseThrow();
        assertThat(polled.getLastPolledUid()).isEqualTo(106);
        assertThat(polled.getLastPollError()).isNull();

        assertThat(enrollmentRepository.findById(e1).orElseThrow().getStatus()).isEqualTo(SequenceEnrollment.RESPONDED);
        SequenceEnrollment b = enrollmentRepository.findById(e2).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(SequenceEnrollment.ACTIVE);
        assertThat(b.skippedChannelSet()).contains(SequenceStep.EMAIL);

        assertThat(emailConsentService.isOptedOut(org, leaver.getEmail())).isTrue();
        assertThat(emailConsentService.view(org, leaver.getEmail().toLowerCase(Locale.ROOT)).get("source"))
                .isEqualTo(EmailConsentService.SOURCE_REPLY);

        List<Activity> onLead = activityRepository.findByOrganizationIdAndRelatedTypeAndRelatedId(org, "Lead", replier.getId());
        assertThat(onLead).singleElement().satisfies(a -> {
            assertThat(a.getType()).isEqualTo("email");
            assertThat(a.getSubject()).isEqualTo("Email reply: Re: Checking in");
            assertThat(a.getBody()).contains("let's talk on Monday");
            assertThat(a.getUserId()).isEqualTo(admin.getId());
        });
        // Out-of-office is not logged; the unsubscribe reply is.
        assertThat(activityRepository.findByOrganizationIdAndRelatedTypeAndRelatedId(org, "Contact", leaver.getId()))
                .singleElement().satisfies(a -> assertThat(a.getSubject()).contains("Spring newsletter"));
        assertThat(activityRepository.findByOrganizationIdAndRelatedTypeAndRelatedId(org, "Lead", bounced.getId())).isEmpty();
    }

    @Test
    void failuresAreRecordedAndOnlyWatchedMailboxesArePolled() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        EmailAccount watched = mailbox(org, admin.getId());
        watched.setPollInbox(true);
        watched.setLastPolledUid(5L);
        watched.setLastPolledUidValidity(1L);
        watched = emailAccountRepository.save(watched);
        EmailAccount ignored = mailbox(org, admin.getId());
        Long watchedId = watched.getId();
        when(inboxFetcher.fetchNew(argThat(a -> a != null && watchedId.equals(a.getId())), any(), any(), anyInt()))
                .thenThrow(new jakarta.mail.AuthenticationFailedException("bad login"));

        poller.pollAll();
        verify(inboxFetcher, never()).fetchNew(argThat(a -> a != null && ignored.getId().equals(a.getId())), any(), any(), anyInt());
        EmailAccount after = emailAccountRepository.findById(watchedId).orElseThrow();
        assertThat(after.getLastPollError()).contains("IMAP login failed");
        assertThat(after.getLastPolledUid()).isEqualTo(5L);   // high-water mark unchanged

        // Toggle via the API: needs an IMAP host; turning it on resets the baseline.
        EmailAccount noImap = mailbox(org, admin.getId());
        noImap.setImapHost(null);
        noImap = emailAccountRepository.save(noImap);
        patchAs(admin, "/api/ai-automations/email-accounts/" + noImap.getId(), Map.of("pollInbox", true))
                .andExpect(status().isBadRequest());
        patchAs(admin, "/api/ai-automations/email-accounts/" + ignored.getId(), Map.of("pollInbox", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pollInbox").value(true));
        patchAs(user(org, Role.MANAGER), "/api/ai-automations/email-accounts/" + ignored.getId(), Map.of("pollInbox", false))
                .andExpect(status().isForbidden());
        patchAs(user(newOrg(), Role.SUPER_ADMIN), "/api/ai-automations/email-accounts/" + ignored.getId(), Map.of("pollInbox", false))
                .andExpect(status().isNotFound());
        assertThat(emailAccountRepository.findById(ignored.getId()).orElseThrow().isPollInbox()).isTrue();

        // Admin console shows the polling mailbox count and SMS status.
        var system = body(getAs(admin, "/api/admin/system").andExpect(status().isOk()));
        assertThat(system.path("features").path("emailInboxPolling").asInt()).isEqualTo(2);
        assertThat(system.path("features").has("smsAzureCommunication")).isTrue();
    }

    @Test
    void sequencesSkipEmailStepsForUnsubscribedAddresses() throws Exception {
        Long org = newOrg();
        User admin = user(org, Role.SUPER_ADMIN);
        mailbox(org, admin.getId());
        Lead l = lead(org, admin.getId(), "Opted Out", uniqueEmail("oo"), null);
        emailConsentService.record(org, l.getEmail(), EmailConsent.OPTED_OUT, EmailConsentService.SOURCE_LINK, "link", null, null);
        long seq = sequenceWithEmailStep(admin, 0);
        long e = enroll(admin, seq, "Lead", l.getId());
        SequenceEnrollment en = enrollmentRepository.findById(e).orElseThrow();
        en.setNextRunAt(Instant.now().minusSeconds(1));
        enrollmentRepository.save(en);

        engine.tick();
        assertThat(executionRepository.findByEnrollmentIdOrderByStepIndexAsc(e)).singleElement().satisfies(x -> {
            assertThat(x.getStatus()).isEqualTo("SKIPPED");
            assertThat(x.getDetail()).isEqualTo("Unsubscribed from email");
        });
        assertThat(mail.sent).isEmpty();
    }
}
