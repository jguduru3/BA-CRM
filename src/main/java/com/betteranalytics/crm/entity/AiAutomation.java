package com.betteranalytics.crm.entity;

import com.betteranalytics.crm.security.EncryptedStringConverter;
import com.betteranalytics.crm.security.SecretCrypto;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "ai_automations")
@Getter @Setter
public class AiAutomation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    // manual | scheduled | on_lead_created | on_stage_change | on_campaign_approved | webhook
    @Column(name = "trigger_type", nullable = false, length = 50)
    private String triggerType;

    // For scheduled: {"cron":"0 9 * * MON-FRI"}; for webhook: {"secret":"..."}
    @Column(name = "trigger_config_json", columnDefinition = "TEXT")
    private String triggerConfigJson;

    // generate_content | send_email | read_email | analyze_data | prepare_strategy | web_search | custom_api
    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    // Prompt template, SMTP/IMAP config, API endpoint, output field, etc.
    @Column(name = "action_config_json", columnDefinition = "TEXT")
    private String actionConfigJson;

    @Column(name = "api_provider", length = 30)
    private String apiProvider = "anthropic";

    // User-provided API key — encrypted at rest, write-only in JSON (responses
    // expose apiKeySet / apiKeyHint instead).
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "api_key_encrypted", columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "model_id", length = 100)
    private String modelId = "claude-opus-5-5";

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "run_count", nullable = false)
    private int runCount = 0;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @JsonProperty(value = "apiKeySet", access = JsonProperty.Access.READ_ONLY)
    public boolean apiKeySet() {
        return apiKeyEncrypted != null && !apiKeyEncrypted.isBlank();
    }

    @JsonProperty(value = "apiKeyHint", access = JsonProperty.Access.READ_ONLY)
    public String apiKeyHint() {
        return SecretCrypto.hint(apiKeyEncrypted);
    }
}
