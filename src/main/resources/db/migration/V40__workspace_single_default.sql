-- V40: exactly one default workspace per organization, enforced by the database.
--
-- Partial unique indexes ("... WHERE is_default") are Postgres-only, so the
-- invariant is expressed with a nullable mirror column instead:
--   default_org_id = organization_id  on the org's default workspace,
--   default_org_id = NULL             on every other workspace.
-- A plain UNIQUE index on default_org_id then allows any number of NULLs but
-- at most one row per organization — on both H2 and PostgreSQL. A CHECK keeps
-- the mirror column in lock-step with is_default.

ALTER TABLE workspaces ADD COLUMN default_org_id BIGINT;

-- Backfill: keep the oldest existing default per org, demote any extra ones.
UPDATE workspaces SET default_org_id = organization_id
 WHERE is_default = TRUE
   AND id = (SELECT MIN(w2.id) FROM workspaces w2
              WHERE w2.organization_id = workspaces.organization_id AND w2.is_default = TRUE);
UPDATE workspaces SET is_default = FALSE WHERE is_default = TRUE AND default_org_id IS NULL;

-- Orgs that have workspaces but no default: promote the oldest one.
UPDATE workspaces SET is_default = TRUE, default_org_id = organization_id
 WHERE id = (SELECT MIN(w2.id) FROM workspaces w2 WHERE w2.organization_id = workspaces.organization_id)
   AND organization_id NOT IN (SELECT w3.organization_id FROM workspaces w3 WHERE w3.default_org_id IS NOT NULL);

CREATE UNIQUE INDEX uq_workspaces_default_org ON workspaces(default_org_id);

ALTER TABLE workspaces ADD CONSTRAINT ck_workspaces_default_mirror CHECK (
    (is_default = TRUE AND default_org_id IS NOT NULL AND default_org_id = organization_id)
    OR (is_default = FALSE AND default_org_id IS NULL));

CREATE INDEX idx_workspaces_org_name ON workspaces(organization_id, name);
