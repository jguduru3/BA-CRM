package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.entity.EmailAccount;

import java.util.List;
import java.util.Map;

/**
 * The outbound edge of the sequence engine: one call per step, never inside a
 * DB transaction. The default implementation delegates to the existing
 * WhatsApp / SMTP services (which carry their own connect/read timeouts).
 */
public interface SequenceChannelSender {

    enum Outcome {
        SENT,
        /** The recipient can't be reached on this channel (bounce, rejected number…): skip the channel from now on. */
        FAILED_PERMANENT,
        /** Outage / timeout / configuration: this step failed, later steps on the channel still run. */
        FAILED_TRANSIENT
    }

    record Result(Outcome outcome, String detail, String waMessageId, Long whatsappMessageId) {
        public static Result sent(String detail) { return new Result(Outcome.SENT, detail, null, null); }
        public static Result permanent(String detail) { return new Result(Outcome.FAILED_PERMANENT, detail, null, null); }
        public static Result transientFailure(String detail) { return new Result(Outcome.FAILED_TRANSIENT, detail, null, null); }
    }

    Result sendEmail(EmailAccount account, String to, String subject, String body);

    Result sendWhatsAppTemplate(Long orgId, Long accountId, String phone, String templateName, String language,
                                List<Map<String, Object>> components, String relatedType, Long relatedId, Long sentBy);
}
