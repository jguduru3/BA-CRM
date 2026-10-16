package com.betteranalytics.crm.sequences;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one IMAP message into a {@link SequenceSignals.InboundEmail}: who sent
 * it, whether it is an auto-reply (RFC 3834 Auto-Submitted, X-Autoreply /
 * X-Autorespond, Precedence: auto_reply|bulk|junk|list, out-of-office
 * subjects) and whether it is a bounce (multipart/report delivery-status,
 * mailer-daemon / postmaster senders) with its failed recipients
 * (X-Failed-Recipients or the DSN's Final-Recipient fields).
 */
public final class InboundEmailClassifier {

    private InboundEmailClassifier() {}

    private static final Pattern AUTO_SUBJECT = Pattern.compile(
            "^(auto(matic)?[ -]?(reply|response)|out of (the )?office|autoreply|auto:|abwesenheit|absence)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_RECIPIENT = Pattern.compile(
            "(?im)^(?:final|original)-recipient:\\s*rfc822;\\s*<?([^>\\s]+@[^>\\s]+)>?");
    private static final int MAX_REPORT_BYTES = 64 * 1024;

    public static SequenceSignals.InboundEmail classify(Long orgId, Message m) {
        try {
            String from = firstAddress(m.getFrom());
            String subject = m.getSubject();
            Instant received = m.getReceivedDate() != null ? m.getReceivedDate().toInstant()
                    : m.getSentDate() != null ? m.getSentDate().toInstant() : null;

            String contentType = m.getContentType() == null ? "" : m.getContentType().toLowerCase(Locale.ROOT);
            String fromLower = from == null ? "" : from.toLowerCase(Locale.ROOT);
            boolean report = (contentType.contains("multipart/report") && contentType.contains("delivery-status"))
                    || fromLower.startsWith("mailer-daemon@") || fromLower.startsWith("postmaster@");

            List<String> failed = new ArrayList<>();
            if (report) {
                String[] hdr = m.getHeader("X-Failed-Recipients");
                if (hdr != null) {
                    for (String h : hdr) for (String a : h.split(",")) if (!a.isBlank()) failed.add(a.trim());
                }
                if (failed.isEmpty()) failed.addAll(finalRecipients(m));
            }
            return new SequenceSignals.InboundEmail(orgId, from, subject, received,
                    !report && isAutoReply(m, subject), report, failed);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isAutoReply(Message m, String subject) throws Exception {
        String auto = header(m, "Auto-Submitted");
        if (auto != null && !auto.trim().toLowerCase(Locale.ROOT).startsWith("no")) return true;
        if (header(m, "X-Autoreply") != null || header(m, "X-Autorespond") != null
                || header(m, "X-Autoreply-From") != null) return true;
        String prec = header(m, "Precedence");
        if (prec != null && Set.of("auto_reply", "bulk", "junk", "list").contains(prec.trim().toLowerCase(Locale.ROOT))) return true;
        return subject != null && AUTO_SUBJECT.matcher(subject.trim()).find();
    }

    private static String header(Message m, String name) throws Exception {
        String[] v = m.getHeader(name);
        return v == null || v.length == 0 ? null : v[0];
    }

    private static String firstAddress(Address[] addresses) {
        if (addresses == null) return null;
        for (Address a : addresses) {
            if (a instanceof InternetAddress ia && ia.getAddress() != null) return ia.getAddress();
        }
        return null;
    }

    private static List<String> finalRecipients(Part p) {
        List<String> out = new ArrayList<>();
        try {
            if (p.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) p.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    if (bp.isMimeType("message/delivery-status") || bp.isMimeType("text/plain")) {
                        Matcher mt = FINAL_RECIPIENT.matcher(read(bp));
                        while (mt.find()) out.add(mt.group(1));
                        if (!out.isEmpty()) return out;
                    }
                }
            }
        } catch (Exception ignored) {
            // an unreadable report just yields no recipients
        }
        return out;
    }

    private static String read(Part p) throws Exception {
        try (InputStream in = p.getInputStream()) {
            return new String(in.readNBytes(MAX_REPORT_BYTES), StandardCharsets.UTF_8);
        }
    }
}
