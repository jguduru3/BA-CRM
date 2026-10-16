package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.WhatsAppMessageRepository;
import com.betteranalytics.crm.service.WhatsAppConsentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Close-style execution: exit on reply, skip on missing data, per-channel skip after failure, at-most-once. */
class SequenceEngineTest extends SequenceTestSupport {

    @Autowired WhatsAppConsentService consentService;
    @Autowired WhatsAppMessageRepository messageRepository;
    @Autowired ApplicationEventPublisher events;

    @Test
    void stepsRunInOrderWithVariablesAndDelays() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        EmailAccount box = mailbox(org, rep.getId());
        waAccount(org);
        String to = uniqueEmail();
        String phone = uniquePhone();
        Lead lead = lead(org, rep.getId(), to, phone);
        User manager = user(org, Role.MANAGER);
        long seq = createSequence(manager, email(0, "Hi {{lead.name}}", "From {{owner.name}}"), whatsapp(2),
                task(1, "Call {{record.name}}"));
        long enr = enroll(rep, seq, "Lead", lead.getId());

        assertThat(engine.tick()).isGreaterThanOrEqualTo(1);
        verify(sender).sendEmail(argThat(a -> a.getId().equals(box.getId())), eq(to.toLowerCase()),
                eq("Hi " + lead.getName()), eq("From " + rep.getName()));
        SequenceEnrollment e = enrollment(enr);
        assertThat(e.getCurrentStep()).isEqualTo(1);
        assertThat(e.getNextRunAt()).isAfter(Instant.now().plusSeconds(2 * 86400 - 120));

        engine.tick();   // not due yet
        verify(sender, never()).sendWhatsAppTemplate(anyLong(), anyLong(), eq(phone), any(), any(), any(), any(), any(), any());

        makeDue(enr);
        engine.tick();
        String first = lead.getName().split(" ")[0];
        verify(sender).sendWhatsAppTemplate(eq(org), anyLong(), eq(phone), eq("follow_up"), eq("en_US"),
                argThat(c -> c.toString().contains("text=" + first)), eq("Lead"), eq(lead.getId()), eq(rep.getId()));

        makeDue(enr);
        engine.tick();
        e = enrollment(enr);
        assertThat(e.getStatus()).isEqualTo("COMPLETED");
        assertThat(e.getActiveKey()).isNull();
        SequenceStepExecution taskExec = executions(enr).get(2);
        Task t = taskRepository.findById(taskExec.getTaskId()).orElseThrow();
        assertThat(t.getTitle()).isEqualTo("Call " + lead.getName());
        assertThat(t.getAssigneeId()).isEqualTo(rep.getId());
        assertThat(t.getRelatedType()).isEqualTo("Lead");
        assertThat(executions(enr)).extracting(SequenceStepExecution::getStatus).containsExactly("SENT", "SENT", "SENT");
    }

    @Test
    void inboundWhatsAppReplyMarksRespondedAndStopsFurtherSteps() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        mailbox(org, rep.getId());
        WhatsAppAccount acct = waAccount(org);
        String phone = uniquePhone();
        String to = uniqueEmail();
        Lead lead = lead(org, rep.getId(), to, phone);
        long seq = createSequence(user(org, Role.MANAGER), whatsapp(0), email(2, "Following up", "Any news?"));
        long enr = enroll(rep, seq, "Lead", lead.getId());
        engine.tick();
        assertThat(enrollment(enr).getCurrentStep()).isEqualTo(1);

        postWebhook(inboundPayload(acct.getPhoneNumberId(), phone.substring(1), "Yes, call me")).andExpect(status().isOk());

        SequenceEnrollment e = enrollment(enr);
        assertThat(e.getStatus()).isEqualTo("RESPONDED");
        assertThat(e.getRespondedAt()).isNotNull();
        makeDue(enr);
        engine.tick();
        verify(sender, never()).sendEmail(any(), eq(to), any(), any());
        assertThat(detail(rep, enr).path("events").findValuesAsText("type")).contains("RESPONDED");
        assertThat(body(getAs(rep, "/api/notifications")).findValuesAsText("type")).contains("sequence_responded");
    }

    @Test
    void inboundEmailReplyExitsButAutoRepliesAndOldMailDoNot() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        mailbox(org, rep.getId());
        String to = uniqueEmail();
        Lead lead = lead(org, rep.getId(), to, null);
        long seq = createSequence(user(org, Role.MANAGER), email(0, "Hi", "Hello"), email(1, "Again", "Hello again"));
        long enr = enroll(rep, seq, "Lead", lead.getId());
        engine.tick();

        Instant now = Instant.now();
        events.publishEvent(new SequenceSignals.InboundEmail(org, to.toUpperCase(), "Automatic reply: away", now, true, false, List.of()));
        events.publishEvent(new SequenceSignals.InboundEmail(org, to, "Old thread", now.minusSeconds(86400), false, false, List.of()));
        assertThat(enrollment(enr).getStatus()).isEqualTo("ACTIVE");

        events.publishEvent(new SequenceSignals.InboundEmail(org, to.toUpperCase(), "Re: Hi", now, false, false, List.of()));
        assertThat(enrollment(enr).getStatus()).isEqualTo("RESPONDED");
        assertThat(enrollment(enr).getStopReason()).isEqualTo("Replied by email");
    }

    @Test
    void stepsWithMissingDataOrOptOutAreSkippedNotFailed() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        mailbox(org, rep.getId());
        waAccount(org);
        String phone = uniquePhone();
        consentService.record(org, phone, WhatsAppConsent.OPTED_OUT, WhatsAppConsentService.SOURCE_MANUAL, "asked", null, null);
        Lead noEmail = lead(org, rep.getId(), null, phone);
        long seq = createSequence(user(org, Role.MANAGER), email(0, "Hi", "Hello"), whatsapp(0), task(0, "Call them"));
        long enr = enroll(rep, seq, "Lead", noEmail.getId());

        for (int i = 0; i < 3; i++) {
            makeDue(enr);
            engine.tick();
        }
        List<SequenceStepExecution> x = executions(enr);
        assertThat(x).extracting(SequenceStepExecution::getStatus).containsExactly("SKIPPED", "SKIPPED", "SENT");
        assertThat(x.get(0).getDetail()).contains("No email address");
        assertThat(x.get(1).getDetail()).contains("Opted out");
        assertThat(enrollment(enr).getStatus()).isEqualTo("COMPLETED");
        verify(sender, never()).sendWhatsAppTemplate(anyLong(), anyLong(), eq(phone), any(), any(), any(), any(), any(), any());

        // A record with no phone at all: WhatsApp step skipped.
        Lead noPhone = lead(org, rep.getId(), uniqueEmail(), null);
        long seq2 = createSequence(user(org, Role.MANAGER), whatsapp(0));
        long enr2 = enroll(rep, seq2, "Lead", noPhone.getId());
        engine.tick();
        assertThat(executions(enr2).get(0).getDetail()).contains("No valid phone");
        assertThat(enrollment(enr2).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void unrecoverableFailureSkipsLaterStepsOfThatChannelOnly() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        mailbox(org, rep.getId());
        waAccount(org);
        String to = uniqueEmail();
        Lead lead = lead(org, rep.getId(), to, uniquePhone());
        when(sender.sendEmail(any(), eq(to.toLowerCase()), any(), any()))
                .thenReturn(SequenceChannelSender.Result.permanent("550 user unknown"));
        long seq = createSequence(user(org, Role.MANAGER),
                email(0, "1", "b"), whatsapp(0), email(0, "2", "b"), whatsapp(0));
        long enr = enroll(rep, seq, "Lead", lead.getId());
        for (int i = 0; i < 4; i++) {
            makeDue(enr);
            engine.tick();
        }
        assertThat(executions(enr)).extracting(SequenceStepExecution::getStatus)
                .containsExactly("FAILED", "SENT", "SKIPPED", "SENT");
        verify(sender, times(1)).sendEmail(any(), eq(to.toLowerCase()), any(), any());
        SequenceEnrollment e = enrollment(enr);
        assertThat(e.getSkippedChannels()).isEqualTo("EMAIL");
        assertThat(e.getStatus()).isEqualTo("COMPLETED");   // a channel failure is not a failed enrollment
    }

    @Test
    void transientFailureDoesNotSkipTheChannel() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        mailbox(org, rep.getId());
        String to = uniqueEmail();
        Lead lead = lead(org, rep.getId(), to, null);
        when(sender.sendEmail(any(), eq(to.toLowerCase()), eq("1"), any()))
                .thenReturn(SequenceChannelSender.Result.transientFailure("timeout"));
        long seq = createSequence(user(org, Role.MANAGER), email(0, "1", "b"), email(0, "2", "b"));
        long enr = enroll(rep, seq, "Lead", lead.getId());
        engine.tick();
        makeDue(enr);
        engine.tick();
        assertThat(executions(enr)).extracting(SequenceStepExecution::getStatus).containsExactly("FAILED", "SENT");
        assertThat(enrollment(enr).getSkippedChannels()).isNull();
    }

    @Test
    void whatsAppFailedStatusWebhookAndEmailBounceSkipTheirChannel() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        mailbox(org, rep.getId());
        WhatsAppAccount acct = waAccount(org);
        String to = uniqueEmail();
        String phone = uniquePhone();
        String waId = "wamid.out-" + UUID.randomUUID();
        when(sender.sendWhatsAppTemplate(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SequenceChannelSender.Result(SequenceChannelSender.Outcome.SENT, "ok", waId, null));
        Lead lead = lead(org, rep.getId(), to, phone);
        long seq = createSequence(user(org, Role.MANAGER), whatsapp(0), whatsapp(1), email(1, "x", "y"), email(1, "z", "y"));
        long enr = enroll(rep, seq, "Lead", lead.getId());
        engine.tick();

        // The outbound message row the real WhatsAppService would have stored.
        WhatsAppMessage m = new WhatsAppMessage();
        m.setOrganizationId(org);
        m.setAccountId(acct.getId());
        m.setDirection("outbound");
        m.setType("template");
        m.setToNumber(phone);
        m.setNormalizedPhone(phone);
        m.setWaMessageId(waId);
        m.setStatus("sent");
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        messageRepository.save(m);
        postWebhook(failedStatusPayload(acct.getPhoneNumberId(), waId)).andExpect(status().isOk());

        assertThat(enrollment(enr).getSkippedChannels()).isEqualTo("WHATSAPP");
        assertThat(executions(enr).get(0).getStatus()).isEqualTo("FAILED");

        makeDue(enr);
        engine.tick();
        assertThat(executions(enr).get(1).getStatus()).isEqualTo("SKIPPED");

        // A bounce (DSN) for the lead's address → later email steps are skipped too.
        events.publishEvent(new SequenceSignals.InboundEmail(org, "MAILER-DAEMON@sender.example", "Undelivered Mail",
                Instant.now(), false, true, List.of(to)));
        assertThat(enrollment(enr).skippedChannelSet()).containsExactlyInAnyOrder("WHATSAPP", "EMAIL");
        makeDue(enr);
        engine.tick();
        assertThat(executions(enr).get(2).getStatus()).isEqualTo("SKIPPED");
        assertThat(enrollment(enr).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentTicksNeverSendTheSameStepTwice() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        mailbox(org, rep.getId());
        long seq = createSequence(user(org, Role.MANAGER), email(0, "Hi", "Hello"), email(5, "Later", "x"));
        List<String> addresses = new ArrayList<>();
        List<Long> enrollments = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String to = uniqueEmail();
            addresses.add(to);
            enrollments.add(enroll(rep, seq, "Lead", lead(org, rep.getId(), to, null).getId()));
        }
        when(sender.sendEmail(any(), any(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(20);   // widen the race window
            return SequenceChannelSender.Result.sent("ok");
        });

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(() -> { start.await(); return engine.tick(); }));
        }
        start.countDown();
        for (Future<Integer> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        for (String to : addresses) verify(sender, times(1)).sendEmail(any(), eq(to), any(), any());
        for (Long id : enrollments) {
            assertThat(executions(id)).hasSize(1);
            assertThat(enrollment(id).getCurrentStep()).isEqualTo(1);
            assertThat(enrollment(id).getClaimedAt()).isNull();
        }
    }

    @Test
    void anInterruptedSendIsNeverRetried() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        mailbox(org, rep.getId());
        String to = uniqueEmail();
        long seq = createSequence(user(org, Role.MANAGER), email(0, "Hi", "Hello"), task(1, "Call"));
        long enr = enroll(rep, seq, "Lead", lead(org, rep.getId(), to, null).getId());

        // Simulate a worker that wrote the PENDING execution row and then died holding the claim.
        SequenceStepExecution pending = new SequenceStepExecution();
        pending.setEnrollmentId(enr);
        pending.setOrganizationId(org);
        pending.setStepIndex(0);
        pending.setChannel("EMAIL");
        pending.setStatus("PENDING");
        pending.setCreatedAt(Instant.now());
        executionRepository.save(pending);
        SequenceEnrollment e = enrollment(enr);
        e.setClaimedAt(Instant.now().minus(SequenceEngine.STALE_CLAIM).minusSeconds(60));
        e.setNextRunAt(Instant.now().minusSeconds(120));
        enrollmentRepository.save(e);

        engine.tick();
        verify(sender, never()).sendEmail(any(), eq(to), any(), any());
        assertThat(executions(enr).get(0).getStatus()).isEqualTo("FAILED");
        assertThat(executions(enr).get(0).getDetail()).contains("not retried");
        assertThat(enrollment(enr).getCurrentStep()).isEqualTo(1);
    }

    @Test
    void deletedRecordsStopTheirEnrollment() throws Exception {
        Long org = newOrg();
        User rep = user(org, Role.EXECUTIVE);
        Lead lead = lead(org, rep.getId(), uniqueEmail(), null);
        long seq = createSequence(user(org, Role.MANAGER), task(0, "Call"));
        long enr = enroll(rep, seq, "Lead", lead.getId());
        lead.setDeletedAt(Instant.now());
        leadRepository.save(lead);
        engine.tick();
        assertThat(enrollment(enr).getStatus()).isEqualTo("STOPPED");
        assertThat(enrollment(enr).getStopReason()).contains("deleted");
    }
}
