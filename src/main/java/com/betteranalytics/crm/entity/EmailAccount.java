package com.betteranalytics.crm.entity;

import com.betteranalytics.crm.security.EncryptedStringConverter;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "email_accounts")
@Getter @Setter
public class EmailAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "email_address", nullable = false, length = 200)
    private String emailAddress;

    @Column(name = "display_name", length = 100)
    private String displayName;

    // smtp_imap | gmail_oauth | outlook_oauth
    @Column(length = 30)
    private String provider = "smtp_imap";

    @Column(name = "smtp_host", length = 200)
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort = 587;

    @Column(name = "smtp_user", length = 200)
    private String smtpUser;

    // Used for both SMTP and IMAP login. Encrypted at rest; write-only in JSON
    // (accepted as "smtpPassword" or "smtpPasswordEncrypted"); responses expose smtpPasswordSet.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @JsonAlias("smtpPassword")
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "smtp_password_encrypted", columnDefinition = "TEXT")
    private String smtpPasswordEncrypted;

    @Column(name = "smtp_tls", nullable = false)
    private boolean smtpTls = true;

    @Column(name = "imap_host", length = 200)
    private String imapHost;

    @Column(name = "imap_port")
    private Integer imapPort = 993;

    @Column(name = "imap_ssl", nullable = false)
    private boolean imapSsl = true;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @JsonProperty(value = "smtpPasswordSet", access = JsonProperty.Access.READ_ONLY)
    public boolean smtpPasswordSet() {
        return smtpPasswordEncrypted != null && !smtpPasswordEncrypted.isBlank();
    }
}
