package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.Activity;
import com.betteranalytics.crm.entity.EmailAccount;
import com.betteranalytics.crm.entity.EmailConsent;
import com.betteranalytics.crm.repository.ActivityRepository;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.repository.EmailAccountRepository;
import com.betteranalytics.crm.repository.LeadRepository;
import com.betteranalytics.crm.sequences.InboundEmailClassifier;
import com.betteranalytics.crm.sequences.SequenceSignals;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

/**
 * Scheduled inbox reader for mailboxes with "Watch inbox for replies" (poll_inbox).
 *
 * Every app.email.inbox-poll.delay-ms (default 5 minutes), on one instance at a time
 * (ShedLock), each enabled mailbox is read for messages that arrived since the last
 * poll (IMAP UID high-water mark, bounded batch, timeouts, folder closed in finally;
 * the first poll only sets the baseline). Each message is:
 *   1. classified ({@link InboundEmailClassifier}) and published as
 *      {@link SequenceSignals.InboundEmail} — sequences stop on a reply (RESPONDED)
 *      and skip later email steps on a bounce;
 *   2. checked for an unsubscribe request (subject / first line "unsubscribe", "stop")
 *      → email_consent opt-out, honoured by campaigns and sequences;
 *   3. when it is a person's reply from a known lead / contact, logged as an
 *      append-only Activity (type "email") on that record.
 * The high-water mark advances after every message, so a crash re-reads at most one.
 */
@Service
public class InboxPoller {

    private static final Logger log = LoggerFactory.getLogger(InboxPoller.class);
    private static final int MAX_BODY = 5_000;

    private final EmailAccountRepository accountRepo;
    private final InboxFetcher fetcher;
    private final EmailConsentService emailConsentService;
    private final LeadRepository leadRepo;
    private final ContactRepository contactRepo;
    private final ActivityRepository activityRepo;
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    private final int batchSize;

    public InboxPoller(EmailAccountRepository accountRepo, InboxFetcher fetcher,
                       EmailConsentService emailConsentService, LeadRepository leadRepo,
                       ContactRepository contactRepo, ActivityRepository activityRepo,
                       ApplicationEventPublisher events,
                       @Value("${app.email.inbox-poll.enabled:true}") boolean enabled,
                       @Value("${app.email.inbox-poll.batch-size:50}") int batchSize) {
        this.accountRepo = accountRepo;
        this.fetcher = fetcher;
        this.emailConsentService = emailConsentService;
        this.leadRepo = leadRepo;
        this.contactRepo = contactRepo;
        this.activityRepo = activityRepo;
        this.events = events;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(500, batchSize));
    }

    @Scheduled(fixedDelayString = "${app.email.inbox-poll.delay-ms:300000}",
               initialDelayString = "${app.email.inbox-poll.initial-delay-ms:60000}")
    @SchedulerLock(name = "email.inboxPoll", lockAtMostFor = "PT15M")
    public void pollAll() {
        if (!enabled) return;
        for (EmailAccount a : accountRepo.findByPollInboxTrueAndIsActiveTrue()) {
            try {
                poll(a);
            } catch (Exception e) {
                log.warn("Inbox poll of mailbox {} failed: {}", a.getId(), e.toString());
            }
        }
    }

    /** Polls one mailbox; returns how many new messages were processed. Never throws for mailbox errors. */
    public int poll(EmailAccount a) {
        if (a.getImapHost() == null || a.getImapHost().isBlank()) {
            accountRepo.recordPollError(a.getId(), Instant.now(), "No IMAP host configured for this mailbox");
            return 0;
        }
        InboxFetcher.Batch batch;
        try {
            batch = fetcher.fetchNew(a, a.getLastPolledUidValidity(), a.getLastPolledUid(), batchSize);
        } catch (Exception e) {
            log.warn("IMAP poll failed for mailbox {}: {}", a.getId(), e.toString());
            accountRepo.recordPollError(a.getId(), Instant.now(), pollError(e));
            return 0;
        }
        int processed = 0;
        for (InboxFetcher.Fetched f : batch.messages()) {
            try {
                handle(a, f.message());
            } catch (Exception e) {
                log.warn("Inbound message {} of mailbox {} could not be processed: {}", f.uid(), a.getId(), e.toString());
            }
            accountRepo.recordPoll(a.getId(), f.uid(), batch.uidValidity(), Instant.now(), null);
            processed++;
        }
        accountRepo.recordPoll(a.getId(), batch.highestUid(), batch.uidValidity(), Instant.now(), null);
        return processed;
    }

    private void handle(EmailAccount a, Message m) throws Exception {
        Long org = a.getOrganizationId();
        SequenceSignals.InboundEmail signal = InboundEmailClassifier.classify(org, m);
        if (signal == null || signal.fromAddress() == null) return;
        String from = EmailConsentService.normalize(signal.fromAddress());
        if (from == null || from.equalsIgnoreCase(a.getEmailAddress())) return;   // our own sent copies

        try {
            events.publishEvent(signal);   // sequences: RESPONDED on reply, skip email on bounce
        } catch (Exception e) {
            log.warn("Inbound email listener failed: {}", e.toString());
        }
        if (signal.deliveryReport() || signal.autoReply()) return;

        String body = textOf(m);
        if (EmailConsentService.isUnsubscribeRequest(signal.subject(), body)
                && !emailConsentService.isOptedOut(org, from)) {
            emailConsentService.record(org, from, EmailConsent.OPTED_OUT, EmailConsentService.SOURCE_REPLY,
                    "Replied \"unsubscribe\" to " + a.getEmailAddress(), null, null);
        }
        logActivity(a, from, signal, body);
    }

    private void logActivity(EmailAccount a, String from, SequenceSignals.InboundEmail signal, String body) {
        Long org = a.getOrganizationId();
        String relatedType = null;
        Long relatedId = null;
        var lead = leadRepo.findFirstByOrganizationIdAndEmailNormalizedOrderByIdAsc(org, from);
        if (lead.isPresent()) {
            relatedType = "Lead";
            relatedId = lead.get().getId();
        } else {
            var contact = contactRepo.findFirstByOrganizationIdAndEmailNormalizedOrderByIdAsc(org, from);
            if (contact.isPresent()) {
                relatedType = "Contact";
                relatedId = contact.get().getId();
            }
        }
        if (relatedType == null) return;   // not a known record: nothing to log
        Instant now = Instant.now();
        Activity act = new Activity();
        act.setOrganizationId(org);
        act.setType("email");
        String subject = signal.subject() == null || signal.subject().isBlank() ? "(no subject)" : signal.subject().trim();
        act.setSubject(truncate("Email reply: " + subject, 255));
        act.setBody(truncate("From " + from + " to " + a.getEmailAddress() + "\n\n" + (body == null ? "" : body), MAX_BODY + 200));
        act.setUserId(a.getUserId());
        act.setRelatedType(relatedType);
        act.setRelatedId(relatedId);
        act.setOutcome("received");
        act.setCompletedAt(signal.receivedAt() != null ? signal.receivedAt() : now);
        act.setCreatedAt(now);
        activityRepo.save(act);
    }

    /** First text/plain part (or a text/html part stripped of tags), capped. */
    static String textOf(Part p) {
        try {
            String t = text(p, 0);
            if (t == null) return null;
            t = t.replace("\r\n", "\n").trim();
            return t.length() > MAX_BODY ? t.substring(0, MAX_BODY) : t;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(Part p, int depth) throws Exception {
        if (depth > 5) return null;
        if (p.isMimeType("text/plain")) return read(p);
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            String html = null;
            for (int i = 0; i < mp.getCount(); i++) {
                Part bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) return read(bp);
                if (bp.isMimeType("multipart/*")) {
                    String nested = text(bp, depth + 1);
                    if (nested != null) return nested;
                }
                if (html == null && bp.isMimeType("text/html")) html = read(bp);
            }
            return html == null ? null : stripTags(html);
        }
        if (p.isMimeType("text/html")) return stripTags(read(p));
        return null;
    }

    private static String read(Part p) throws Exception {
        Object c = p.getContent();
        if (c instanceof String s) return s.length() > MAX_BODY * 4 ? s.substring(0, MAX_BODY * 4) : s;
        try (InputStream in = p.getInputStream()) {
            return new String(in.readNBytes(MAX_BODY * 4), StandardCharsets.UTF_8);
        }
    }

    private static String stripTags(String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", " ")
                .replaceAll("[ \\t]+", " ");
    }

    private static String pollError(Exception e) {
        String type = e.getClass().getSimpleName();
        String msg = type.toLowerCase(Locale.ROOT).contains("authentication") ? "IMAP login failed — check the mailbox password"
                : e instanceof IllegalArgumentException iae ? iae.getMessage()
                : "Could not read the inbox (" + type + ")";
        return truncate(msg, 300);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Mailboxes currently watched (admin console). */
    public long watchedCount() {
        return accountRepo.countByPollInboxTrueAndIsActiveTrue();
    }
}
