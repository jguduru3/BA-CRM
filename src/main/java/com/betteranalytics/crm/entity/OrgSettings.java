package com.betteranalytics.crm.entity;

import com.betteranalytics.crm.security.EncryptedStringConverter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "org_settings")
@Getter @Setter
public class OrgSettings {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    // openai | anthropic
    @Column(name = "ai_provider", length = 50)
    private String aiProvider = "openai";

    // Encrypted at rest; never serialized (OrgSettingsController exposes aiKeySet / aiKeyHint).
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ai_api_key", columnDefinition = "TEXT")
    private String aiApiKey;

    // e.g. gpt-4o-mini, gpt-4o, claude-haiku-4-5-20251001
    @Column(name = "ai_model", length = 100)
    private String aiModel = "gpt-4o-mini";

    @Column(name = "ai_enabled", nullable = false)
    private boolean aiEnabled = false;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
