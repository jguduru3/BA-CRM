-- V60 — Pipeline intelligence: append-only opportunity stage history.
-- Portable across PostgreSQL 16 and H2 (MODE=PostgreSQL): plain TIMESTAMP, no partial indexes.
--
-- One row is written when a deal is created (from_stage NULL) and on every stage change.
-- Rows are never updated or deleted. Durations are stored on the row that ENDS a stint,
-- so velocity and medians are plain AVG / PERCENTILE_CONT over columns:
--   seconds_in_from_stage  time the deal spent in from_stage before this change
--   seconds_since_created  time from the deal's creation to this change (days-to-close)
CREATE TABLE opportunity_stage_history (
    id                    BIGSERIAL PRIMARY KEY,
    opportunity_id        BIGINT NOT NULL REFERENCES opportunities(id),
    organization_id       BIGINT NOT NULL REFERENCES organizations(id),
    from_stage            VARCHAR(30),
    to_stage              VARCHAR(30) NOT NULL,
    changed_by            BIGINT,
    entered_at            TIMESTAMP NOT NULL,
    seconds_in_from_stage BIGINT,
    seconds_since_created BIGINT
);
CREATE INDEX idx_opp_stage_history_opp ON opportunity_stage_history (opportunity_id, entered_at);
CREATE INDEX idx_opp_stage_history_org_from ON opportunity_stage_history (organization_id, from_stage);
CREATE INDEX idx_opp_stage_history_org_to ON opportunity_stage_history (organization_id, to_stage);

-- Backfill: one row per existing deal (its current stage, entered when the deal was created).
-- Durations are unknown for these rows and stay NULL (excluded from velocity averages).
INSERT INTO opportunity_stage_history (opportunity_id, organization_id, from_stage, to_stage, changed_by, entered_at)
SELECT id, organization_id, NULL, stage, owner_id, COALESCE(created_at, updated_at, CURRENT_TIMESTAMP)
FROM opportunities;
