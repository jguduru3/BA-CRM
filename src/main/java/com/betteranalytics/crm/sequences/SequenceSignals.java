package com.betteranalytics.crm.sequences;

import java.time.Instant;
import java.util.List;

/**
 * Application events published by the messaging services (WhatsApp webhook,
 * IMAP read path) and consumed by {@link SequenceEngine}. Publishing them is
 * the only coupling between those services and sequences.
 */
public final class SequenceSignals {

    private SequenceSignals() {}

    /** Any inbound WhatsApp message from {@code normalizedPhone} (E.164) to this org. */
    public record WhatsAppInbound(Long organizationId, String normalizedPhone, String messageType, Instant receivedAt) {}

    /** A WhatsApp message we sent reached the terminal "failed" delivery status. */
    public record WhatsAppDeliveryFailed(Long organizationId, String waMessageId, String reason) {}

    /**
     * One message seen on the IMAP read path.
     *
     * @param autoReply         Auto-Submitted / X-Autoreply / Precedence / out-of-office subject detected
     * @param deliveryReport    a bounce / DSN (multipart/report, mailer-daemon, postmaster)
     * @param failedRecipients  for a bounce: the recipients that failed (X-Failed-Recipients / Final-Recipient)
     */
    public record InboundEmail(Long organizationId, String fromAddress, String subject, Instant receivedAt,
                               boolean autoReply, boolean deliveryReport, List<String> failedRecipients) {}
}
