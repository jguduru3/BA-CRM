package com.betteranalytics.crm.service;

/**
 * Outbound SMS edge (default: {@link AzureSmsSender}, Azure Communication Services).
 * Never called inside a DB transaction; implementations carry their own timeouts.
 */
public interface SmsSender {

    enum Outcome {
        SENT,
        /** The gateway rejected this recipient / message: do not retry. */
        FAILED_PERMANENT,
        /** Nothing was accepted (gateway unreachable, auth, throttled, 5xx): safe to retry later. */
        NOT_SENT,
        /** Outcome unknown (e.g. read timeout): treated as failed to stay at-most-once. */
        UNKNOWN
    }

    record Result(Outcome outcome, String messageId, String detail) {}

    boolean isConfigured();

    /** E.164 sender number, or null when not configured. */
    String fromNumber();

    /**
     * Sends one message. {@code idempotencyKey} is passed to the gateway as the
     * repeatability request id, so a retried request is not delivered twice.
     */
    Result send(String toE164, String message, java.util.UUID idempotencyKey);
}
