package com.betteranalytics.crm.channels;

import com.betteranalytics.crm.entity.*;
import com.betteranalytics.crm.repository.*;
import com.betteranalytics.crm.service.CampaignDispatcher;
import com.betteranalytics.crm.service.InboxFetcher;
import com.betteranalytics.crm.service.MailSenderFactory;
import com.betteranalytics.crm.service.SmsSender;
import com.betteranalytics.crm.support.IntegrationTest;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Channel tests replace only the outbound / inbound edges — the SMTP sender
 * factory (a fake transport that records messages), the SMS gateway and the IMAP
 * reader — with fakes. Claims, dispatch, consent, permissions and the HTTP API are real.
 */
abstract class ChannelTestSupport extends IntegrationTest {

    @MockBean protected MailSenderFactory mailSenderFactory;
    @MockBean protected SmsSender smsSender;
    @MockBean protected InboxFetcher inboxFetcher;

    @Autowired protected CampaignDispatcher dispatcher;
    @Autowired protected CampaignRepository campaignRepository;
    @Autowired protected CampaignContactRepository recipientRepository;
    @Autowired protected EmailAccountRepository emailAccountRepository;
    @Autowired protected LeadRepository leadRepository;
    @Autowired protected ContactRepository contactRepository;

    protected FakeMailSender mail;

    @BeforeEach
    void fakeTransport() {
        mail = new FakeMailSender();
        when(mailSenderFactory.create(any())).thenReturn(mail);
        when(smsSender.isConfigured()).thenReturn(false);
    }

    /** SMTP transport that records every message instead of connecting. */
    static class FakeMailSender extends JavaMailSenderImpl {
        final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
        final Map<String, RuntimeException> failFor = new ConcurrentHashMap<>();
        volatile RuntimeException failAll;
        volatile Consumer<MimeMessage> onSend = m -> {};

        @Override
        protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
            for (MimeMessage m : mimeMessages) {
                if (failAll != null) throw failAll;
                String to = to(m);
                RuntimeException e = failFor.get(to);
                if (e != null) throw e;
                sent.add(m);
                onSend.accept(m);
            }
        }

        List<String> recipients() {
            return sent.stream().map(FakeMailSender::to).toList();
        }

        static String to(MimeMessage m) {
            try {
                Address[] a = m.getRecipients(Message.RecipientType.TO);
                return a == null ? null : ((InternetAddress) a[0]).getAddress();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    protected EmailAccount mailbox(Long orgId, Long userId) {
        EmailAccount a = new EmailAccount();
        a.setOrganizationId(orgId);
        a.setUserId(userId);
        a.setEmailAddress("sales-" + UUID.randomUUID().toString().substring(0, 6) + "@sender.example");
        a.setDisplayName("Sales Team");
        a.setSmtpHost("smtp.example.com");
        a.setSmtpPasswordEncrypted("secret");
        a.setImapHost("imap.example.com");
        a.setActive(true);
        a.setCreatedAt(Instant.now());
        return emailAccountRepository.save(a);
    }

    protected Lead lead(Long orgId, Long ownerId, String name, String email, String phone) {
        Lead l = new Lead();
        l.setOrganizationId(orgId);
        l.setName(name);
        l.setEmail(email);
        l.setPhone(phone);
        l.setOwnerId(ownerId);
        l.setCreatedAt(Instant.now());
        l.setUpdatedAt(Instant.now());
        return leadRepository.save(l);
    }

    protected Contact contact(Long orgId, Long ownerId, String name, String email) {
        Contact c = new Contact();
        c.setOrganizationId(orgId);
        c.setName(name);
        c.setEmail(email);
        c.setOwnerId(ownerId);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return contactRepository.save(c);
    }

    /** A campaign row already in 'sending' (for driving the dispatcher directly). */
    protected Campaign sendingCampaign(Long orgId, Long userId, String type, EmailAccount account) {
        Campaign c = new Campaign();
        c.setOrganizationId(orgId);
        c.setName(type + " direct " + UUID.randomUUID().toString().substring(0, 4));
        c.setType(type);
        c.setStatus("sending");
        c.setEmailAccountId(account == null ? null : account.getId());
        c.setEmailSubject("Hello {{contact.firstName}}");
        c.setEmailBody("Hi {{contact.name}}, news from {{campaign.name}}.");
        c.setMessageBody("Hi {{contact.firstName}}, reply STOP to opt out");
        c.setCreatedBy(userId);
        c.setOwnerId(userId);
        c.setStartedAt(Instant.now());
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        return campaignRepository.save(c);
    }

    protected CampaignContact emailRecipient(Campaign c, String email) {
        CampaignContact cc = new CampaignContact();
        cc.setCampaignId(c.getId());
        cc.setOrganizationId(c.getOrganizationId());
        cc.setEmail(email);
        cc.setEmailNormalized(email == null ? null : email.toLowerCase(Locale.ROOT));
        cc.setName("Recipient " + (email == null ? "?" : email.substring(0, email.indexOf('@'))));
        cc.setStatus("pending");
        cc.setCreatedAt(Instant.now());
        return recipientRepository.save(cc);
    }

    protected CampaignContact smsRecipient(Campaign c, String phone) {
        CampaignContact cc = new CampaignContact();
        cc.setCampaignId(c.getId());
        cc.setOrganizationId(c.getOrganizationId());
        cc.setPhoneNumber(phone);
        cc.setNormalizedPhone(phone);
        cc.setName("Ravi Kumar");
        cc.setStatus("pending");
        cc.setCreatedAt(Instant.now());
        return recipientRepository.save(cc);
    }

    protected Map<String, String> statusByEmail(long campaignId) {
        Map<String, String> m = new HashMap<>();
        recipientRepository.findByCampaignId(campaignId).forEach(cc -> m.put(cc.getEmailNormalized(), cc.getStatus()));
        return m;
    }

    protected String campaignStatus(long id) {
        return campaignRepository.findById(id).orElseThrow().getStatus();
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@client.example";
    }

    protected static String uniquePhone() {
        return "+9196" + String.format("%08d", (long) (Math.random() * 100_000_000L));
    }

    /** Polls until the condition holds (background executor work), failing after ~15s. */
    protected static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
