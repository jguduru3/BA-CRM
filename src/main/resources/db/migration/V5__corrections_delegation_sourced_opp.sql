-- V5__corrections_delegation_sourced_opp.sql
-- Implements spec gaps identified from CRM-Role_based_Features (Sep 23 2026):
--   1. Activity corrections  — append-only "fix" entries linked to original
--   2. Activity duration     — call/meeting duration in minutes
--   3. Lead follow-up reschedule counter — Manager visibility per spec §4
--   4. Lead score override reason        — Manager override logged per spec §3
--   5. Opportunity back-link to source lead — enables Inside Sales "own sourced" visibility §5
--   6. Delegations table                 — temporary Manager-level delegation §7 build notes

-- ── 1+2. Activity: correction linkage + duration ─────────────────────────────
-- correction_for_id: points to the original activity this entry corrects.
-- The timeline stays append-only; a "correction" is just a new row that links
-- back to the original, so anyone reading the timeline can follow the chain.
ALTER TABLE activities ADD COLUMN correction_for_id BIGINT REFERENCES activities(id);
ALTER TABLE activities ADD COLUMN duration_minutes  INT;   -- call/meeting duration

CREATE INDEX idx_activities_correction ON activities(correction_for_id);

-- ── 3. Lead: follow-up reschedule counter ────────────────────────────────────
-- Incremented every time follow_up_at changes on an existing lead.
-- The Manager dashboard shows this count as a coaching signal.
ALTER TABLE leads ADD COLUMN follow_up_reschedule_count INT NOT NULL DEFAULT 0;

-- ── 4. Lead: score override reason ───────────────────────────────────────────
-- Stored alongside lead_score; only written when a Manager overrides the
-- system-calculated score via POST /api/leads/{id}/score-override.
ALTER TABLE leads ADD COLUMN lead_score_override_reason VARCHAR(500);

-- ── 5. Opportunity: source lead back-link ────────────────────────────────────
-- Set during lead→opportunity conversion (LeadController.convert()).
-- Allows Inside Sales to discover and view (read-only, metadata) the stage
-- of opportunities that originated from leads they qualified.
ALTER TABLE opportunities ADD COLUMN source_lead_id     BIGINT REFERENCES leads(id);
ALTER TABLE opportunities ADD COLUMN sourced_by_user_id BIGINT REFERENCES users(id);

CREATE INDEX idx_opportunities_sourced_by ON opportunities(sourced_by_user_id);
CREATE INDEX idx_opportunities_source_lead ON opportunities(source_lead_id);

-- ── 6. Delegations ────────────────────────────────────────────────────────────
-- A Manager going on leave grants a named delegate TEAM scope for a date range.
-- The delegate inherits access to the delegator's teams for that window only.
-- PermissionService reads this table during isInTeamScope() resolution.
CREATE TABLE delegations (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    delegator_id    BIGINT NOT NULL REFERENCES users(id),   -- the Manager granting access
    delegate_id     BIGINT NOT NULL REFERENCES users(id),   -- who receives access
    team_id         BIGINT REFERENCES teams(id),            -- NULL = all of delegator's teams
    start_date      DATE NOT NULL,
    end_date        DATE NOT NULL,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT delegations_dates_check CHECK (end_date >= start_date),
    CONSTRAINT delegations_no_self_delegate CHECK (delegator_id != delegate_id)
);

CREATE INDEX idx_delegations_org      ON delegations(organization_id);
CREATE INDEX idx_delegations_delegate ON delegations(delegate_id, start_date, end_date);
CREATE INDEX idx_delegations_delegator ON delegations(delegator_id);
