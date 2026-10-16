-- V62 — Simple per-user forecast quota per period (month or quarter).
CREATE TABLE forecast_quotas (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    user_id         BIGINT NOT NULL REFERENCES users(id),
    period_type     VARCHAR(10) NOT NULL,
    period_start    DATE NOT NULL,
    amount          BIGINT NOT NULL,
    updated_by      BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_forecast_quota_user_period UNIQUE (user_id, period_type, period_start)
);
CREATE INDEX idx_forecast_quotas_org_period ON forecast_quotas (organization_id, period_type, period_start);
