-- V4__soft_delete.sql
-- Soft-delete for the 5 core CRM objects. Every existing DELETE endpoint
-- today does a hard `repository.delete(...)` — this migration adds the
-- columns; the Java-side change (below, same step) switches DELETE to set
-- these instead of destroying the row, and adds the entities' Hibernate
-- @SQLRestriction so soft-deleted rows disappear from every normal query
-- automatically (list, search, findById, dashboards) without having to
-- remember a "WHERE deleted_at IS NULL" on each one by hand.
--
-- True hard-delete (SuperAdmin, retention-policy-only per the spec) is
-- deliberately NOT built here — that's a scheduled purge job, a later step,
-- not part of "soft-delete infrastructure."

ALTER TABLE leads ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE leads ADD COLUMN deleted_by BIGINT REFERENCES users(id);
ALTER TABLE leads ADD COLUMN delete_reason VARCHAR(500);

ALTER TABLE companies ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE companies ADD COLUMN deleted_by BIGINT REFERENCES users(id);
ALTER TABLE companies ADD COLUMN delete_reason VARCHAR(500);

ALTER TABLE contacts ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE contacts ADD COLUMN deleted_by BIGINT REFERENCES users(id);
ALTER TABLE contacts ADD COLUMN delete_reason VARCHAR(500);

ALTER TABLE opportunities ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE opportunities ADD COLUMN deleted_by BIGINT REFERENCES users(id);
ALTER TABLE opportunities ADD COLUMN delete_reason VARCHAR(500);

ALTER TABLE tasks ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE tasks ADD COLUMN deleted_by BIGINT REFERENCES users(id);
ALTER TABLE tasks ADD COLUMN delete_reason VARCHAR(500);

CREATE INDEX idx_leads_deleted_at ON leads(deleted_at);
CREATE INDEX idx_companies_deleted_at ON companies(deleted_at);
CREATE INDEX idx_contacts_deleted_at ON contacts(deleted_at);
CREATE INDEX idx_opportunities_deleted_at ON opportunities(deleted_at);
CREATE INDEX idx_tasks_deleted_at ON tasks(deleted_at);
