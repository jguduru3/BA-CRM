-- V2__lead_activity_opportunity_qualification_fields.sql
-- Adds the deal-qualification fields discussed for Lead, Activity, and Opportunity.
-- Additive only — no existing column is renamed, retyped, or dropped, so this is
-- safe to run against a database that already has V1 applied and has live data.

-- ── Lead: qualification / discovery fields ──────────────────────
ALTER TABLE leads ADD COLUMN industry VARCHAR(100);
ALTER TABLE leads ADD COLUMN geography VARCHAR(150);
ALTER TABLE leads ADD COLUMN employee_band VARCHAR(30);      -- e.g. "1-10", "11-50", "51-200", "201-1000", "1000+"
ALTER TABLE leads ADD COLUMN revenue_band VARCHAR(30);        -- e.g. "<1Cr", "1-5Cr", "5-25Cr", "25Cr+"
ALTER TABLE leads ADD COLUMN requirement TEXT;
ALTER TABLE leads ADD COLUMN pain_point TEXT;
ALTER TABLE leads ADD COLUMN technology_used VARCHAR(300);
ALTER TABLE leads ADD COLUMN decision_maker VARCHAR(200);    -- name/title of the actual decision maker contact
ALTER TABLE leads ADD COLUMN lead_score INT DEFAULT 0;

CREATE INDEX idx_leads_score ON leads(lead_score);
CREATE INDEX idx_leads_industry ON leads(industry);

-- ── Activity: follow-up tracking ─────────────────────────────────
-- scheduled_at/completed_at already describe THIS activity's own timing;
-- next_follow_up_at is a distinct field for "when should we touch this
-- record again", independent of whether this particular activity is done.
ALTER TABLE activities ADD COLUMN next_follow_up_at TIMESTAMP;
-- NOTE: 'type' has no DB-level CHECK constraint (validated in code, if at all),
-- so no migration is needed to allow 'linkedin' as a value — see LINKEDIN_TYPE
-- note in Activity.java. This comment documents that decision for anyone
-- reading the migration history looking for it.

-- ── Opportunity: deal-context fields ─────────────────────────────
ALTER TABLE opportunities ADD COLUMN service_product VARCHAR(200);
ALTER TABLE opportunities ADD COLUMN competitors VARCHAR(300);
ALTER TABLE opportunities ADD COLUMN current_vendor VARCHAR(200);
ALTER TABLE opportunities ADD COLUMN proposal_status VARCHAR(30);      -- Not Sent | Sent | Viewed | Revised
ALTER TABLE opportunities ADD COLUMN negotiation_status VARCHAR(30);   -- Not Started | In Progress | Final Offer | Agreed
ALTER TABLE opportunities ADD COLUMN po_status VARCHAR(30);            -- Not Requested | Requested | Received | Payment Pending | Paid
