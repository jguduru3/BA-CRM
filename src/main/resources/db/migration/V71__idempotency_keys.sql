-- V71: Idempotency-Key support for POST/PATCH under /api/** (Stripe / IETF draft
-- semantics). One row per (credential, key); request_hash = SHA-256 of
-- method + path + body. Rows are pruned after 24h by a ShedLock job.
-- Portable across PostgreSQL 16 and H2 (MODE=PostgreSQL). Additive only.

CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,
    credential_key  VARCHAR(100)  NOT NULL,           -- 'apikey:<id>' or 'user:<id>'
    idem_key        VARCHAR(255)  NOT NULL,
    request_hash    VARCHAR(64)   NOT NULL,
    status          VARCHAR(20)   NOT NULL,           -- IN_PROGRESS | COMPLETED
    response_status INT,
    response_body   TEXT,
    content_type    VARCHAR(200),
    created_at      TIMESTAMP     NOT NULL,
    completed_at    TIMESTAMP,
    CONSTRAINT uq_idempotency_keys UNIQUE (credential_key, idem_key)
);
CREATE INDEX idx_idempotency_keys_created ON idempotency_keys(created_at);
