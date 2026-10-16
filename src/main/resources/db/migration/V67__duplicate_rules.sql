-- V67 — duplicate rules (Salesforce model): matching rules find candidates
-- (dedupe.DuplicateDetector), duplicate rules decide what happens. One row per
-- organization and object type; no row = the default (enabled, WARN on create and edit).
CREATE TABLE duplicate_rules (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT      NOT NULL REFERENCES organizations(id),
    object_type      VARCHAR(20) NOT NULL,                 -- LEAD | CONTACT | COMPANY
    action_on_create VARCHAR(10) NOT NULL DEFAULT 'WARN',  -- ALLOW | WARN | BLOCK
    action_on_edit   VARCHAR(10) NOT NULL DEFAULT 'WARN',
    enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_by       BIGINT,
    updated_at       TIMESTAMP,
    CONSTRAINT uq_duplicate_rules_org_type UNIQUE (organization_id, object_type)
);
