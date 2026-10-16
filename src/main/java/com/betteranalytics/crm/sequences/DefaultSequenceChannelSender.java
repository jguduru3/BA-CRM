package com.betteranalytics.crm.sequences;

import com.betteranalytics.crm.entity.EmailAccount;
import com.betteranalytics.crm.entity.WhatsAppMessage;
import com.betteranalytics.crm.service.EmailIntegrationService;
import com.betteranalytics.crm.service.WhatsAppService;
import jakarta.mail.SendFailedException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Sends sequence steps through {@link EmailIntegrationService} and {@link WhatsAppService}. */
@Component
public class DefaultSequenceChannelSender implements SequenceChannelSender {

    /** SMTP permanent negative completion (5yz) in an exception message, e.g. "550 5.1.1 user unknown". */
    private static final Pattern SMTP_5XX = Pattern.compile("(^|\\D)5\\d\\d[ \\-]");

    private final EmailIntegrationService emailService;
    private final WhatsAppService whatsAppService;

    public DefaultSequenceChannelSender(EmailIntegrationService emailService, WhatsAppService whatsAppService) {
        this.emailService = emailService;
        this.whatsAppService = whatsAppService;
    }

    @Override
    public Result sendEmail(EmailAccount account, String to, String subject, String body) {
        try {
            emailService.sendEmail(account, to, subject, body);
            return Result.sent("Email sent to " + to);
        } catch (IllegalArgumentException e) {
            return Result.permanent(e.getMessage());           // not a deliverable address
        } catch (Exception e) {
            return isPermanentSmtpFailure(e)
                    ? Result.permanent("Email rejected by the mail server (address undeliverable)")
                    : Result.transientFailure("Email could not be sent: " + e.getMessage());
        }
    }

    static boolean isPermanentSmtpFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SendFailedException) return true;
            if (t instanceof MailSendException mse) {
                for (Exception inner : mse.getMessageExceptions()) {
                    if (isPermanentSmtpFailure(inner)) return true;
                }
            }
            if (t instanceof jakarta.mail.MessagingException && t.getMessage() != null
                    && SMTP_5XX.matcher(t.getMessage()).find()) return true;
        }
        return false;
    }

    @Override
    public Result sendWhatsAppTemplate(Long orgId, Long accountId, String phone, String templateName, String language,
                                       List<Map<String, Object>> components, String relatedType, Long relatedId,
                                       Long sentBy) {
        WhatsAppMessage msg;
        try {
            msg = whatsAppService.sendTemplate(orgId, accountId, phone, templateName, language, components,
                    relatedType, relatedId, sentBy);
        } catch (IllegalArgumentException e) {
            return Result.permanent(e.getMessage());
        } catch (IllegalStateException e) {
            // opted out (raced the pre-check) or the account was deactivated
            return Result.transientFailure(e.getMessage());
        } catch (Exception e) {
            return Result.transientFailure("WhatsApp send failed");
        }
        if ("failed".equals(msg.getStatus())) {
            // Graph API 400 = Meta rejected this recipient/message (e.g. 131026 undeliverable).
            Outcome o = "400".equals(msg.getErrorCode()) ? Outcome.FAILED_PERMANENT : Outcome.FAILED_TRANSIENT;
            return new Result(o, msg.getErrorMessage(), msg.getWaMessageId(), msg.getId());
        }
        return new Result(Outcome.SENT, "WhatsApp template " + templateName + " sent", msg.getWaMessageId(), msg.getId());
    }
}
