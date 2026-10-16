package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.EmailAccount;
import com.betteranalytics.crm.sequences.InboundEmailClassifier;
import com.betteranalytics.crm.sequences.SequenceSignals;
import com.betteranalytics.crm.security.UrlSafety;
import jakarta.mail.*;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Sends email via dynamic per-account SMTP credentials (not global Spring config).
 * Reads inbox via IMAP using stored credentials (encrypted at rest — see
 * EmailAccount / EncryptedStringConverter).
 *
 * Hardening: hosts must resolve to public addresses (same rules as UrlSafety),
 * ports are allow-listed, every connection has 10s connect/read/write timeouts,
 * TLS certificates are validated normally (no ssl.trust override), and
 * generated mail is sent as plain text.
 */
@Service
public class EmailIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(EmailIntegrationService.class);

    public static final Set<Integer> SMTP_PORTS = Set.of(25, 465, 587, 2525);
    public static final Set<Integer> IMAP_PORTS = Set.of(143, 993);
    public static final int MAX_READ_LIMIT = 50;

    /** Messages seen on the IMAP read path are published for sequences (reply exit, bounce skip). */
    @Autowired(required = false)
    private ApplicationEventPublisher events;
    private static final String TIMEOUT_MS = "10000";
    private static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,60}$");

    /** SMTP sender seam (tests); the hardened {@link #buildSender} is used when absent. */
    @Autowired(required = false)
    private MailSenderFactory senderFactory;
    private static final Pattern FOLDER = Pattern.compile("^[\\p{L}\\p{N} ._/\\-\\[\\]]{1,100}$");

    // ── Validation (also used by AiAutomationController when accounts are created) ──

    /** Host must be a plain hostname/IP resolving only to public addresses. */
    public static String requireSafeHost(String host, String field) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException(field + " is required");
        String h = host.trim();
        if (!HOSTNAME.matcher(h).matches()) throw new IllegalArgumentException(field + " must be a hostname");
        try {
            UrlSafety.requireSafe("https://" + h + "/");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": " + e.getMessage().replace("url", "host"));
        }
        return h;
    }

    public static int requireSmtpPort(Integer port) {
        int p = port != null ? port : 587;
        if (!SMTP_PORTS.contains(p)) throw new IllegalArgumentException("smtpPort must be one of " + SMTP_PORTS);
        return p;
    }

    public static int requireImapPort(Integer port) {
        int p = port != null ? port : 993;
        if (!IMAP_PORTS.contains(p)) throw new IllegalArgumentException("imapPort must be one of " + IMAP_PORTS);
        return p;
    }

    /** Exactly one syntactically valid address, no header-injection characters. */
    public static InternetAddress requireSingleAddress(String to) {
        if (to == null || to.isBlank()) throw new IllegalArgumentException("recipient address is required");
        String t = to.trim();
        if (t.indexOf('\r') >= 0 || t.indexOf('\n') >= 0 || t.indexOf(',') >= 0 || t.indexOf(';') >= 0)
            throw new IllegalArgumentException("recipient must be exactly one email address");
        try {
            InternetAddress[] parsed = InternetAddress.parse(t, true);
            if (parsed.length != 1) throw new IllegalArgumentException("recipient must be exactly one email address");
            parsed[0].validate();
            return parsed[0];
        } catch (AddressException e) {
            throw new IllegalArgumentException("recipient is not a valid email address");
        }
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    public void sendEmail(EmailAccount account, String to, String subject, String body) {
        try {
            sendRaw(account, to, subject, body, Map.of());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("SMTP send failed for account {}: {}", account.getId(), e.toString());
            throw new IllegalStateException("Failed to send email via the configured SMTP account", e);
        }
    }

    /**
     * Campaign send: plain-text message with extra headers (List-Unsubscribe,
     * Precedence…). Returns the Message-ID. Exceptions are thrown unwrapped so
     * the caller can tell "nothing was sent" (connect / login) from a permanent
     * rejection (5xx) — see {@link #classifyFailure}.
     */
    public String sendCampaignEmail(EmailAccount account, String to, String subject, String body,
                                    Map<String, String> headers) throws Exception {
        return sendRaw(account, to, subject, body, headers);
    }

    private String sendRaw(EmailAccount account, String to, String subject, String body,
                           Map<String, String> headers) throws Exception {
        InternetAddress recipient = requireSingleAddress(to);
        JavaMailSender sender = senderFactory != null ? senderFactory.create(account) : buildSender(account);
        MimeMessage msg = sender.createMimeMessage();
        msg.setFrom(new InternetAddress(account.getEmailAddress(),
                account.getDisplayName() != null ? account.getDisplayName() : account.getEmailAddress()));
        msg.setRecipients(Message.RecipientType.TO, new Address[]{recipient});
        msg.setSubject(stripLineBreaks(subject), "UTF-8");
        // Plain text: AI output / template values can never inject markup or scripts.
        msg.setText(body != null ? body : "", "UTF-8");
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (h.getValue() == null || !HEADER_NAME.matcher(h.getKey()).matches()) continue;
                msg.setHeader(h.getKey(), h.getValue().replaceAll("[\\r\\n]+", " ").trim());
            }
        }
        msg.saveChanges();   // assigns Message-ID
        String messageId = msg.getMessageID();
        sender.send(msg);
        log.info("Email sent from account {} to {}", account.getId(), recipient.getAddress());
        return messageId;
    }

    /** How a failed send should be treated by an at-most-once sender. */
    public enum FailureKind {
        /** The server rejected the recipient/message (5xx): do not retry. */
        PERMANENT,
        /** Nothing reached the server (connect / TLS / login failure): safe to retry later. */
        NOT_SENT,
        /** Anything else (e.g. timeout mid-transfer): the message may have been accepted. */
        UNKNOWN
    }

    public static FailureKind classifyFailure(Throwable e) {
        if (com.betteranalytics.crm.sequences.DefaultSequenceChannelSender.isPermanentSmtpFailure(e)) return FailureKind.PERMANENT;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.mail.MailAuthenticationException
                    || t instanceof AuthenticationFailedException
                    || "MailConnectException".equals(t.getClass().getSimpleName())
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof javax.net.ssl.SSLHandshakeException) return FailureKind.NOT_SENT;
            if (t instanceof org.springframework.mail.MailSendException mse && mse.getMessage() != null
                    && mse.getMessage().contains("connection failed")) return FailureKind.NOT_SENT;
        }
        return FailureKind.UNKNOWN;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> readEmails(EmailAccount account, String folderName, int limit) {
        String host = requireSafeHost(account.getImapHost(), "imapHost");
        int port = requireImapPort(account.getImapPort());
        int max = Math.max(1, Math.min(MAX_READ_LIMIT, limit));
        String folderToOpen = folderName != null && !folderName.isBlank() ? folderName : "INBOX";
        if (!FOLDER.matcher(folderToOpen).matches()) throw new IllegalArgumentException("folder name is not valid");

        List<Map<String, Object>> results = new ArrayList<>();
        List<SequenceSignals.InboundEmail> signals = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            store = connectImap(account, host, port);

            folder = store.getFolder(folderToOpen);
            folder.open(Folder.READ_ONLY);

            int total = folder.getMessageCount();
            if (total > 0) {
                int start = Math.max(1, total - max + 1);
                Message[] messages = folder.getMessages(start, total);
                for (int i = messages.length - 1; i >= 0; i--) {
                    Message m = messages[i];
                    Map<String, Object> email = new LinkedHashMap<>();
                    email.put("subject", m.getSubject());
                    email.put("from", Arrays.toString(m.getFrom()));
                    email.put("sentDate", m.getSentDate() != null ? m.getSentDate().toInstant().toString() : null);
                    email.put("size", m.getSize());
                    try {
                        Object content = m.getContent();
                        email.put("body", content instanceof String s ? s.substring(0, Math.min(s.length(), 2000)) : "[multipart]");
                    } catch (Exception ignored) {
                        email.put("body", "[unreadable]");
                    }
                    results.add(email);
                    SequenceSignals.InboundEmail signal = InboundEmailClassifier.classify(account.getOrganizationId(), m);
                    if (signal != null && signal.fromAddress() != null
                            && !signal.fromAddress().equalsIgnoreCase(account.getEmailAddress())) signals.add(signal);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("IMAP read failed for account {}: {}", account.getId(), e.toString());
            throw new IllegalStateException("Failed to read emails from the configured IMAP account");
        } finally {
            if (folder != null && folder.isOpen()) {
                try { folder.close(false); } catch (Exception ignored) {}
            }
            if (store != null) {
                try { store.close(); } catch (Exception ignored) {}
            }
        }
        if (events != null) {
            for (SequenceSignals.InboundEmail signal : signals) {
                try { events.publishEvent(signal); }
                catch (Exception e) { log.warn("Inbound email listener failed: {}", e.toString()); }
            }
        }
        return results;
    }

    /**
     * Connected IMAP store for the mailbox (validated public host, allow-listed port,
     * TLS with server identity check, 10s connect/read/write timeouts). Caller closes it.
     */
    public Store connectImap(EmailAccount account) throws MessagingException {
        return connectImap(account, requireSafeHost(account.getImapHost(), "imapHost"), requireImapPort(account.getImapPort()));
    }

    private Store connectImap(EmailAccount account, String host, int port) throws MessagingException {
        Properties props = new Properties();
        String protocol = account.isImapSsl() ? "imaps" : "imap";
        props.setProperty("mail.store.protocol", protocol);
        props.setProperty("mail." + protocol + ".host", host);
        props.setProperty("mail." + protocol + ".port", String.valueOf(port));
        props.setProperty("mail." + protocol + ".ssl.enable", String.valueOf(account.isImapSsl()));
        props.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
        if (!account.isImapSsl()) {
            props.setProperty("mail.imap.starttls.enable", "true");
            props.setProperty("mail.imap.starttls.required", "true");
        }
        props.setProperty("mail." + protocol + ".connectiontimeout", TIMEOUT_MS);
        props.setProperty("mail." + protocol + ".timeout", TIMEOUT_MS);
        props.setProperty("mail." + protocol + ".writetimeout", TIMEOUT_MS);
        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);
        try {
            store.connect(host, port, account.getSmtpUser() != null
                    ? account.getSmtpUser() : account.getEmailAddress(),
                    account.getSmtpPasswordEncrypted());
        } catch (MessagingException e) {
            try { store.close(); } catch (Exception ignored) {}
            throw e;
        }
        return store;
    }

    private JavaMailSenderImpl buildSender(EmailAccount account) {
        String host = requireSafeHost(account.getSmtpHost(), "smtpHost");
        int port = requireSmtpPort(account.getSmtpPort());
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(account.getSmtpUser() != null ? account.getSmtpUser() : account.getEmailAddress());
        sender.setPassword(account.getSmtpPasswordEncrypted());
        Properties p = sender.getJavaMailProperties();
        p.setProperty("mail.transport.protocol", "smtp");
        p.setProperty("mail.smtp.auth", "true");
        if (port == 465) {
            p.setProperty("mail.smtp.ssl.enable", "true");          // implicit TLS
        } else if (account.isSmtpTls()) {
            p.setProperty("mail.smtp.starttls.enable", "true");
            p.setProperty("mail.smtp.starttls.required", "true");   // no silent downgrade
        }
        p.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        p.setProperty("mail.smtp.connectiontimeout", TIMEOUT_MS);
        p.setProperty("mail.smtp.timeout", TIMEOUT_MS);
        p.setProperty("mail.smtp.writetimeout", TIMEOUT_MS);
        return sender;
    }

    private static String stripLineBreaks(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n]+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }
}
