-- V21 — indexes behind the database-side filtering / sorting / pagination of the
-- CRM list endpoints, reports and exports (record scope is applied as
-- organization_id + owner_id IN (...) in SQL). Plain indexes only (H2 + PostgreSQL).

-- Leads
CREATE INDEX idx_leads_org_owner      ON leads(organization_id, owner_id);
CREATE INDEX idx_leads_org_stage      ON leads(organization_id, stage);
CREATE INDEX idx_leads_org_updated    ON leads(organization_id, updated_at);
CREATE INDEX idx_leads_company        ON leads(company_id);
CREATE INDEX idx_leads_follow_up      ON leads(follow_up_at, follow_up_notified_at);

-- Opportunities
CREATE INDEX idx_opps_org_owner       ON opportunities(organization_id, owner_id);
CREATE INDEX idx_opps_org_stage       ON opportunities(organization_id, stage);
CREATE INDEX idx_opps_org_updated     ON opportunities(organization_id, updated_at);
CREATE INDEX idx_opps_company         ON opportunities(company_id);
CREATE INDEX idx_opps_contact         ON opportunities(contact_id);

-- One opportunity per converted lead (makes lead conversion idempotent).
-- Legacy duplicates (should not exist: conversion always checked converted_opp_id)
-- keep their back-link only on the oldest opportunity so the unique index can be built.
UPDATE opportunities SET source_lead_id = NULL
 WHERE source_lead_id IS NOT NULL
   AND id NOT IN (SELECT keep_id FROM (SELECT MIN(id) AS keep_id FROM opportunities
                                        WHERE source_lead_id IS NOT NULL GROUP BY source_lead_id) k);
CREATE UNIQUE INDEX uq_opps_source_lead ON opportunities(source_lead_id);

-- Companies / contacts
CREATE INDEX idx_companies_org_name    ON companies(organization_id, name);
CREATE INDEX idx_companies_org_updated ON companies(organization_id, updated_at);
CREATE INDEX idx_contacts_org_owner    ON contacts(organization_id, owner_id);
CREATE INDEX idx_contacts_company      ON contacts(company_id);
CREATE INDEX idx_contacts_org_email    ON contacts(organization_id, email);

-- Tasks
CREATE INDEX idx_tasks_org_assignee    ON tasks(organization_id, assignee_id);
CREATE INDEX idx_tasks_org_due         ON tasks(organization_id, due_date);

-- Activities
CREATE INDEX idx_activities_org_user    ON activities(organization_id, user_id);
CREATE INDEX idx_activities_related     ON activities(organization_id, related_type, related_id);
CREATE INDEX idx_activities_org_created ON activities(organization_id, created_at);
