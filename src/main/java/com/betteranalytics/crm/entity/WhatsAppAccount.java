package com.betteranalytics.crm.entity;

import com.betteranalytics.crm.security.EncryptedStringConverter;
import com.betteranalytics.crm.security.SecretCrypto;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "whatsapp_accounts")
@Getter @Setter
public class WhatsAppAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "phone_number_id", nullable = false, length = 100)
    private String phoneNumberId;

    @Column(name = "business_account_id", length = 100)
    private String businessAccountId;

    // Human-readable phone number, e.g. "+91 98765 43210"
    @Column(name = "display_phone_number", length = 30)
    private String displayPhoneNumber;

    // Encrypted at rest (AES-GCM via SecretCrypto). Write-only in JSON: accepted
    // on create/update, never serialized — responses expose hasAccessToken /
    // accessTokenHint instead.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    // Plain text (the GET verify handshake looks accounts up by it) but write-only in JSON.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "webhook_verify_token", length = 200)
    private String webhookVerifyToken;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @JsonProperty(value = "hasAccessToken", access = JsonProperty.Access.READ_ONLY)
    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    @JsonProperty(value = "accessTokenHint", access = JsonProperty.Access.READ_ONLY)
    public String accessTokenHint() {
        return SecretCrypto.hint(accessToken);
    }

    @JsonProperty(value = "webhookVerifyTokenSet", access = JsonProperty.Access.READ_ONLY)
    public boolean webhookVerifyTokenSet() {
        return webhookVerifyToken != null && !webhookVerifyToken.isBlank();
    }
}
