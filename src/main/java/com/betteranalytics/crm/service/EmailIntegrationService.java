package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.EmailAccount;
import com.betteranalytics.crm.security.UrlSafety;
import jakarta.mail.*;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String TIMEOUT_MS = "10000";
    private static final Pattern HOSTNAME = Pattern.compile("^[A-Za-z0-9.-]{1,253}$");
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
        InternetAddress recipient = requireSingleAddress(to);
        JavaMailSenderImpl sender = buildSender(account);
        try {
            MimeMessage msg = sender.createMimeMessage();
            msg.setFrom(new InternetAddress(account.getEmailAddress(),
                    account.getDisplayName() != null ? account.getDisplayName() : account.getEmailAddress()));
            msg.setRecipients(Message.RecipientType.TO, new Address[]{recipient});
            msg.setSubject(stripLineBreaks(subject), "UTF-8");
            // Plain text: AI output / template values can never inject markup or scripts.
            msg.setText(body != null ? body : "", "UTF-8");
            sender.send(msg);
            log.info("Email sent from account {} to {}", account.getId(), recipient.getAddress());
        } catch (Exception e) {
            log.warn("SMTP send failed for account {}: {}", account.getId(), e.toString());
            throw new IllegalStateException("Failed to send email via the configured SMTP account");
        }
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> readEmails(EmailAccount account, String folderName, int limit) {
        String host = requireSafeHost(account.getImapHost(), "imapHost");
        int port = requireImapPort(account.getImapPort());
        int max = Math.max(1, Math.min(MAX_READ_LIMIT, limit));
        String folderToOpen = folderName != null && !folderName.isBlank() ? folderName : "INBOX";
        if (!FOLDER.matcher(folderToOpen).matches()) throw new IllegalArgumentException("folder name is not valid");

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

        List<Map<String, Object>> results = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            Session session = Session.getInstance(props);
            store = session.getStore(protocol);
            store.connect(host, port, account.getSmtpUser() != null
                    ? account.getSmtpUser() : account.getEmailAddress(),
                    account.getSmtpPasswordEncrypted());

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
        return results;
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
