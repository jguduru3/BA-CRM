package com.betteranalytics.crm.sequences;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.SendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.util.Date;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Reply / auto-reply / bounce detection on the IMAP read path, and SMTP failure classification. */
class InboundEmailClassifierTest {

    private static MimeMessage message(String from, String subject) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setSubject(subject);
        m.setSentDate(new Date());
        m.setText("hello");
        m.saveChanges();
        return m;
    }

    @Test
    void aPlainReplyIsAReply() throws Exception {
        var s = InboundEmailClassifier.classify(7L, message("Asha <asha@client.example>", "Re: our call"));
        assertThat(s.fromAddress()).isEqualTo("asha@client.example");
        assertThat(s.autoReply()).isFalse();
        assertThat(s.deliveryReport()).isFalse();
        assertThat(s.organizationId()).isEqualTo(7L);
    }

    @Test
    void autoRepliesAreDetected() throws Exception {
        MimeMessage a = message("asha@client.example", "Re: hi");
        a.setHeader("Auto-Submitted", "auto-replied");
        assertThat(InboundEmailClassifier.classify(1L, a).autoReply()).isTrue();

        MimeMessage no = message("asha@client.example", "Re: hi");
        no.setHeader("Auto-Submitted", "no");
        assertThat(InboundEmailClassifier.classify(1L, no).autoReply()).isFalse();

        MimeMessage p = message("asha@client.example", "Re: hi");
        p.setHeader("Precedence", "auto_reply");
        assertThat(InboundEmailClassifier.classify(1L, p).autoReply()).isTrue();

        assertThat(InboundEmailClassifier.classify(1L, message("asha@client.example", "Out of Office: back Monday")).autoReply()).isTrue();
        assertThat(InboundEmailClassifier.classify(1L, message("asha@client.example", "Automatic reply: hi")).autoReply()).isTrue();
    }

    @Test
    void bouncesYieldTheFailedRecipient() throws Exception {
        MimeMessage x = message("MAILER-DAEMON@mx.example", "Undelivered Mail Returned to Sender");
        x.setHeader("X-Failed-Recipients", "gone@client.example");
        var s = InboundEmailClassifier.classify(1L, x);
        assertThat(s.deliveryReport()).isTrue();
        assertThat(s.failedRecipients()).containsExactly("gone@client.example");

        String raw = """
                From: Mail Delivery System <postmaster@mx.example>
                Subject: Delivery Status Notification (Failure)
                MIME-Version: 1.0
                Content-Type: multipart/report; report-type=delivery-status; boundary="b1"

                --b1
                Content-Type: text/plain

                Your message could not be delivered.
                --b1
                Content-Type: message/delivery-status

                Reporting-MTA: dns; mx.example

                Final-Recipient: rfc822; nobody@client.example
                Action: failed
                Status: 5.1.1
                --b1--
                """.replace("\n", "\r\n");
        MimeMessage dsn = new MimeMessage(Session.getInstance(new Properties()),
                new java.io.ByteArrayInputStream(raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var d = InboundEmailClassifier.classify(1L, dsn);
        assertThat(d.deliveryReport()).isTrue();
        assertThat(d.failedRecipients()).containsExactly("nobody@client.example");
    }

    @Test
    void smtpRejectionsArePermanentOutagesAreNot() {
        assertThat(DefaultSequenceChannelSender.isPermanentSmtpFailure(
                new IllegalStateException("x", new MailSendException(Map.of(new Object(), new SendFailedException("Invalid Addresses")))))).isTrue();
        assertThat(DefaultSequenceChannelSender.isPermanentSmtpFailure(
                new IllegalStateException("x", new jakarta.mail.MessagingException("550 5.1.1 <x@y>: Recipient address rejected")))).isTrue();
        assertThat(DefaultSequenceChannelSender.isPermanentSmtpFailure(
                new IllegalStateException("x", new MailSendException("Couldn't connect to host")))).isFalse();
    }
}
