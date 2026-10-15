package com.betteranalytics.crm.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A Master API credential. The plaintext key ("bcrm_" + random) is returned
 * exactly once at creation; only its SHA-256 is persisted, so a database leak
 * does not leak usable keys. Requests made with a key run as actingUserId and
 * go through the same PermissionService checks as that user's own sessions.
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
public class ApiKey {
    public static final String SCOPE_READ = "read";
    public static final String SCOPE_READ_WRITE = "read_write";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 150)
    private String name;

    /** First characters of the key, shown in the UI so keys can be told apart. */
    @Column(name = "key_prefix", nullable = false, length = 20)
    private String keyPrefix;

    @JsonIgnore
    @Column(name = "key_hash", nullable = false, length = 64, unique = true)
    private String keyHash;

    @Column(nullable = false, length = 20)
    private String scope = SCOPE_READ;

    @Column(name = "acting_user_id", nullable = false)
    private Long actingUserId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_used_ip", length = 64)
    private String lastUsedIp;

    @Column(name = "request_count", nullable = false)
    private long requestCount = 0;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean usableAt(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean writeAllowed() {
        return SCOPE_READ_WRITE.equals(scope);
    }
}
