package com.betteranalytics.crm.service;

import com.betteranalytics.crm.entity.EmailAccount;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Builds the SMTP sender for one mailbox. The default ({@link EmailIntegrationService})
 * builds a hardened JavaMailSenderImpl with timeouts; tests replace this bean with a
 * fake that captures messages instead of opening a connection.
 */
public interface MailSenderFactory {
    JavaMailSender create(EmailAccount account);
}
