-- V80: email campaigns are really dispatched (SMTP via an org EmailAccount) and
-- every email recipient can unsubscribe (List-Unsubscribe + email_consent store,
-- honoured by campaigns and sequences).
-- Portable across PostgreSQL 16 and H2 (MODE=PostgreSQL). Nothing is dropped.

-- ── 1. Campaign: sending mailbox + subject/body templates ─────────────────────
ALTER TABLE campaigns ADD COLUMN email_account_id BIGINT;
ALTER TABLE campaigns ADD COLUMN email_subject VARCHAR(300);
ALTER TABLE campaigns ADD COLUMN email_body TEXT;

-- ── 2. Recipients: email address (raw + normalized lowercase) ─────────────────
-- Email recipients may have no phone number, so the phone column becomes optional
-- (phone-channel validation stays in CampaignController). NULL keys never collide,
-- so phone-only and email-only recipients coexist under both unique constraints.
ALTER TABLE campaign_contacts ALTER COLUMN phone_number DROP NOT NULL;
ALTER TABLE campaign_contacts ADD COLUMN email VARCHAR(320);
ALTER TABLE campaign_contacts ADD COLUMN email_normalized VARCHAR(320);
ALTER TABLE campaign_contacts
    ADD CONSTRAINT uq_campaign_contacts_campaign_email UNIQUE (campaign_id, email_normalized);
CREATE INDEX idx_campaign_contacts_email ON campaign_contacts(organization_id, email_normalized);

-- ── 3. Email opt-out store (current state + append-only history) ──────────────
CREATE TABLE email_consent (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT       NOT NULL,
    email_normalized VARCHAR(320) NOT NULL,
    status           VARCHAR(20)  NOT NULL,          -- opted_in | opted_out
    source           VARCHAR(30),                    -- unsubscribe_link | reply_keyword | manual
    reason           VARCHAR(500),
    updated_by       BIGINT,
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP,
    CONSTRAINT uq_email_consent_org_email UNIQUE (organization_id, email_normalized)
);

CREATE TABLE email_consent_events (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT       NOT NULL,
    email_normalized VARCHAR(320) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    source           VARCHAR(30),
    reason           VARCHAR(500),
    actor_user_id    BIGINT,
    campaign_id      BIGINT,
    created_at       TIMESTAMP
);
CREATE INDEX idx_email_consent_events_lookup ON email_consent_events(organization_id, email_normalized, created_at);
