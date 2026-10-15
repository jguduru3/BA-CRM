-- V13 — production hardening + the Master API integration layer.
--
-- 1. Account security on users: brute-force lockout, forced password change
--    for the seeded admin, and a token version so a password change / reset /
--    deactivation / "sign out everywhere" invalidates every JWT issued before it.
-- 2. api_keys — hashed, org-scoped keys for the Master API (/api/master/v1/**).
--    Only the SHA-256 of a key is stored; the plaintext is shown once at creation.
-- 3. webhook_subscriptions / webhook_deliveries — outbound, HMAC-signed event
--    notifications so other platforms can react to CRM changes in real time.

ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN failed_login_count   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until         TIMESTAMP;
ALTER TABLE users ADD COLUMN token_version        INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN last_login_at        TIMESTAMP;

-- The V1 seed admin still on its published dev password must rotate it on first sign-in.
UPDATE users SET must_change_password = TRUE
 WHERE email = 'admin@betteranalytics.in'
   AND password = '$2b$10$t.gq/UfxLzcHm7Ajl5hWFOezhj8v5Ytb70U/OM0a7lUqcxjWEPpFu';

-- Email lookups at sign-in are case-insensitive from now on.
UPDATE users SET email = LOWER(TRIM(email));

CREATE TABLE api_keys (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organizations(id),
    name             VARCHAR(150) NOT NULL,
    key_prefix       VARCHAR(20)  NOT NULL,
    key_hash         VARCHAR(64)  NOT NULL UNIQUE,
    scope            VARCHAR(20)  NOT NULL DEFAULT 'read',   -- read | read_write
    acting_user_id   BIGINT       NOT NULL REFERENCES users(id),
    created_by       BIGINT       NOT NULL REFERENCES users(id),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    expires_at       TIMESTAMP,
    last_used_at     TIMESTAMP,
    last_used_ip     VARCHAR(64),
    request_count    BIGINT       NOT NULL DEFAULT 0,
    revoked_at       TIMESTAMP
);
CREATE INDEX idx_api_keys_org ON api_keys(organization_id);

CREATE TABLE webhook_subscriptions (
    id                    BIGSERIAL PRIMARY KEY,
    organization_id       BIGINT        NOT NULL REFERENCES organizations(id),
    name                  VARCHAR(150)  NOT NULL,
    url                   VARCHAR(1000) NOT NULL,
    secret                TEXT          NOT NULL,
    events                TEXT          NOT NULL,             -- comma-separated, '*' = everything
    is_active             BOOLEAN       NOT NULL DEFAULT TRUE,
    consecutive_failures  INTEGER       NOT NULL DEFAULT 0,
    last_delivery_at      TIMESTAMP,
    last_status_code      INTEGER,
    created_by            BIGINT        NOT NULL REFERENCES users(id),
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_subs_org ON webhook_subscriptions(organization_id);

CREATE TABLE webhook_deliveries (
    id               BIGSERIAL PRIMARY KEY,
    subscription_id  BIGINT       NOT NULL REFERENCES webhook_subscriptions(id) ON DELETE CASCADE,
    organization_id  BIGINT       NOT NULL REFERENCES organizations(id),
    event            VARCHAR(100) NOT NULL,
    delivery_uid     VARCHAR(64)  NOT NULL,
    attempt          INTEGER      NOT NULL DEFAULT 1,
    status_code      INTEGER,
    success          BOOLEAN      NOT NULL DEFAULT FALSE,
    duration_ms      BIGINT,
    error            VARCHAR(1000),
    request_body     TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_webhook_deliveries_sub ON webhook_deliveries(subscription_id, created_at);

-- Speeds up the audit-log screen and the webhook/export paths that read it.
CREATE INDEX idx_audit_events_org_created ON audit_events(organization_id, created_at);
