-- V61 — Forecast categories (HubSpot model) and deal health on opportunities.
-- Portable across PostgreSQL 16 and H2 (MODE=PostgreSQL).

-- Ordered forecast category: NOT_FORECASTED < PIPELINE < BEST_CASE < COMMIT < CLOSED_WON.
-- Synced from the stage mapping on every stage change unless the rep overrode it.
ALTER TABLE opportunities ADD COLUMN forecast_category VARCHAR(20);
ALTER TABLE opportunities ADD COLUMN forecast_category_overridden BOOLEAN NOT NULL DEFAULT FALSE;
-- How many times the expected close date was pushed later.
ALTER TABLE opportunities ADD COLUMN close_date_slips INT NOT NULL DEFAULT 0;
-- When the deal entered its current stage (days-in-stage for the health score).
ALTER TABLE opportunities ADD COLUMN stage_entered_at TIMESTAMP;
-- Rules-based deal health 0..100, the explaining factors (JSON array) and the raised risk flags
-- (comma-separated, used to notify once per flag change).
ALTER TABLE opportunities ADD COLUMN health_score INT;
ALTER TABLE opportunities ADD COLUMN health_factors TEXT;
ALTER TABLE opportunities ADD COLUMN health_flags VARCHAR(200);
ALTER TABLE opportunities ADD COLUMN health_computed_at TIMESTAMP;

-- Existing deals: default stage → category mapping (no org had a custom mapping before V61).
UPDATE opportunities SET forecast_category = CASE stage
    WHEN 'Qualification' THEN 'PIPELINE'
    WHEN 'Proposal'      THEN 'PIPELINE'
    WHEN 'Negotiation'   THEN 'BEST_CASE'
    WHEN 'Verbal Commit' THEN 'COMMIT'
    WHEN 'Won'           THEN 'CLOSED_WON'
    WHEN 'Lost'          THEN 'NOT_FORECASTED'
    ELSE 'PIPELINE' END;
UPDATE opportunities SET stage_entered_at = COALESCE(created_at, updated_at);

-- Per-org stage → forecast category mapping (JSON object), NULL = defaults.
ALTER TABLE pipeline_settings ADD COLUMN stage_forecast_categories TEXT;

-- Forecast buckets filter on close date within an org.
CREATE INDEX idx_opportunities_org_close_date ON opportunities (organization_id, close_date);
