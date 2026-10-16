-- V65 — data quality: normalized duplicate-match keys on leads, contacts and companies.
-- Values are computed in Java (dedupe.MatchKeys) on every insert/update; existing rows
-- are backfilled by the V66 Java migration with exactly the same functions.
-- merged_ids records the ids folded into a record by an (irreversible) merge.

ALTER TABLE leads ADD COLUMN email_normalized VARCHAR(255);
ALTER TABLE leads ADD COLUMN phone_normalized VARCHAR(32);
ALTER TABLE leads ADD COLUMN domain_normalized VARCHAR(255);
ALTER TABLE leads ADD COLUMN name_key VARCHAR(255);
ALTER TABLE leads ADD COLUMN name_phonetic VARCHAR(16);
ALTER TABLE leads ADD COLUMN merged_ids TEXT;

ALTER TABLE contacts ADD COLUMN email_normalized VARCHAR(255);
ALTER TABLE contacts ADD COLUMN phone_normalized VARCHAR(32);
ALTER TABLE contacts ADD COLUMN domain_normalized VARCHAR(255);
ALTER TABLE contacts ADD COLUMN name_key VARCHAR(255);
ALTER TABLE contacts ADD COLUMN name_phonetic VARCHAR(16);
ALTER TABLE contacts ADD COLUMN merged_ids TEXT;

ALTER TABLE companies ADD COLUMN email_normalized VARCHAR(255);
ALTER TABLE companies ADD COLUMN phone_normalized VARCHAR(32);
ALTER TABLE companies ADD COLUMN domain_normalized VARCHAR(255);
ALTER TABLE companies ADD COLUMN name_key VARCHAR(255);
ALTER TABLE companies ADD COLUMN name_phonetic VARCHAR(16);
ALTER TABLE companies ADD COLUMN merged_ids TEXT;

-- Every candidate lookup is "same organization AND key = ?".
CREATE INDEX idx_leads_dq_email    ON leads (organization_id, email_normalized);
CREATE INDEX idx_leads_dq_phone    ON leads (organization_id, phone_normalized);
CREATE INDEX idx_leads_dq_domain   ON leads (organization_id, domain_normalized);
CREATE INDEX idx_leads_dq_name     ON leads (organization_id, name_key);
CREATE INDEX idx_leads_dq_phonetic ON leads (organization_id, name_phonetic);

CREATE INDEX idx_contacts_dq_email    ON contacts (organization_id, email_normalized);
CREATE INDEX idx_contacts_dq_phone    ON contacts (organization_id, phone_normalized);
CREATE INDEX idx_contacts_dq_domain   ON contacts (organization_id, domain_normalized);
CREATE INDEX idx_contacts_dq_name     ON contacts (organization_id, name_key);
CREATE INDEX idx_contacts_dq_phonetic ON contacts (organization_id, name_phonetic);

CREATE INDEX idx_companies_dq_email    ON companies (organization_id, email_normalized);
CREATE INDEX idx_companies_dq_phone    ON companies (organization_id, phone_normalized);
CREATE INDEX idx_companies_dq_domain   ON companies (organization_id, domain_normalized);
CREATE INDEX idx_companies_dq_name     ON companies (organization_id, name_key);
CREATE INDEX idx_companies_dq_phonetic ON companies (organization_id, name_phonetic);
