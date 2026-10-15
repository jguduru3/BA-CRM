-- V20 — CRM core reliability + pipeline features.
-- Portable across PostgreSQL 16 and H2 (MODE=PostgreSQL): plain TIMESTAMP, no partial indexes.

-- ── Optimistic locking (@Version) on records edited by users AND background jobs ──
ALTER TABLE leads         ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE opportunities ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- ── Overdue follow-up notification (sent once; reset when follow_up_at changes) ──
ALTER TABLE leads ADD COLUMN follow_up_notified_at TIMESTAMP;

-- ── Opportunity: manual probability override + Won/Lost reason ──────────────────
ALTER TABLE opportunities ADD COLUMN probability_overridden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE opportunities ADD COLUMN close_reason VARCHAR(500);
ALTER TABLE opportunities ADD COLUMN closed_at TIMESTAMP;

-- ── Per-organisation pipeline settings ───────────────────────────────────────────
-- stage_probabilities: JSON object {"Qualification":10,"Proposal":30,...}
-- big_deal_threshold:  deals at or above this value alert the manager chain + VP/Super Admins
CREATE TABLE pipeline_settings (
    id                  BIGSERIAL PRIMARY KEY,
    organization_id     BIGINT NOT NULL REFERENCES organizations(id),
    stage_probabilities TEXT NOT NULL,
    big_deal_threshold  BIGINT,
    updated_by          BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_pipeline_settings_org UNIQUE (organization_id)
);
