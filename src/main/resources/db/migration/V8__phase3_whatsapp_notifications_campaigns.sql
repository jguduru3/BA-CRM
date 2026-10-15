-- V8__phase3_whatsapp_notifications_campaigns.sql
-- Phase 3: WhatsApp Cloud API, push notifications, and campaign lifecycle

-- ── WhatsApp Business Accounts ───────────────────────────────────────────────
-- Stores the Meta App credentials for each org's WhatsApp Business Account.
-- access_token should be encrypted at rest in production (currently stored plain).
CREATE TABLE whatsapp_accounts (
    id                    BIGSERIAL PRIMARY KEY,
    organization_id       BIGINT NOT NULL REFERENCES organizations(id),
    phone_number_id       VARCHAR(100) NOT NULL,   -- Meta's Phone Number ID
    business_account_id   VARCHAR(100),             -- Meta's WABA ID
    display_phone_number  VARCHAR(30),              -- e.g. "+91 98765 43210"
    access_token          TEXT,                     -- system user access token (or page token)
    webhook_verify_token  VARCHAR(200),             -- our chosen verify token for Meta webhook
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_by            BIGINT REFERENCES users(id),
    created_at            TIMESTAMP NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_whatsapp_accounts_org ON whatsapp_accounts(organization_id);

-- ── WhatsApp Messages ─────────────────────────────────────────────────────────
-- Immutable message log — all inbound and outbound WhatsApp messages.
-- direction: inbound | outbound
-- type: text | image | document | audio | video | template | interactive | sticker | reaction
-- status: queued | sent | delivered | read | failed | received (for inbound)
CREATE TABLE whatsapp_messages (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    account_id      BIGINT REFERENCES whatsapp_accounts(id),
    direction       VARCHAR(10)  NOT NULL,
    wa_message_id   VARCHAR(200),                   -- Meta's message ID
    from_number     VARCHAR(30),
    to_number       VARCHAR(30),
    type            VARCHAR(20)  NOT NULL DEFAULT 'text',
    body            TEXT,
    media_url       VARCHAR(500),
    media_mime_type VARCHAR(100),
    template_name   VARCHAR(100),
    template_params TEXT,                           -- JSON array of template parameter values
    status          VARCHAR(20)  NOT NULL DEFAULT 'queued',
    error_code      VARCHAR(50),
    error_message   VARCHAR(500),
    related_type    VARCHAR(20),                    -- Lead | Contact
    related_id      BIGINT,
    sent_by         BIGINT REFERENCES users(id),    -- null for inbound
    campaign_id     BIGINT,                         -- set when message is part of a campaign
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_wa_messages_org      ON whatsapp_messages(organization_id);
CREATE INDEX idx_wa_messages_related  ON whatsapp_messages(related_type, related_id);
CREATE INDEX idx_wa_messages_wamid    ON whatsapp_messages(wa_message_id);
CREATE INDEX idx_wa_messages_campaign ON whatsapp_messages(campaign_id);

-- ── Device Tokens (push notifications) ──────────────────────────────────────
-- platform: ios | android | web
CREATE TABLE device_tokens (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    user_id         BIGINT NOT NULL REFERENCES users(id),
    token           VARCHAR(500) NOT NULL,
    platform        VARCHAR(10)  NOT NULL DEFAULT 'web',
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (user_id, token)
);
CREATE INDEX idx_device_tokens_user ON device_tokens(user_id);

-- ── Notifications (in-app + trigger for push) ────────────────────────────────
-- type examples: lead_assigned | task_due | opp_stage_changed | wa_message_received
--                delegation_created | lead_score_override | approval_requested
CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    user_id         BIGINT NOT NULL REFERENCES users(id),
    type            VARCHAR(60)  NOT NULL,
    title           VARCHAR(300) NOT NULL,
    body            TEXT,
    related_type    VARCHAR(20),
    related_id      BIGINT,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    read_at         TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user    ON notifications(user_id, is_read);
CREATE INDEX idx_notifications_org     ON notifications(organization_id);
CREATE INDEX idx_notifications_created ON notifications(created_at);

-- ── Campaigns ─────────────────────────────────────────────────────────────────
-- type: whatsapp | email | sms
-- status: draft | scheduled | running | paused | completed | cancelled
CREATE TABLE campaigns (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(200) NOT NULL,
    type            VARCHAR(20)  NOT NULL DEFAULT 'whatsapp',
    status          VARCHAR(20)  NOT NULL DEFAULT 'draft',
    wa_account_id   BIGINT REFERENCES whatsapp_accounts(id),
    template_name   VARCHAR(100),                   -- pre-approved WhatsApp template
    template_params TEXT,                           -- JSON array of param definitions
    message_body    TEXT,                           -- for session messages (non-template)
    scheduled_at    TIMESTAMP,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    total_contacts  INT NOT NULL DEFAULT 0,
    sent_count      INT NOT NULL DEFAULT 0,
    delivered_count INT NOT NULL DEFAULT 0,
    read_count      INT NOT NULL DEFAULT 0,
    failed_count    INT NOT NULL DEFAULT 0,
    created_by      BIGINT REFERENCES users(id),
    owner_id        BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_campaigns_org    ON campaigns(organization_id);
CREATE INDEX idx_campaigns_status ON campaigns(status);

-- ── Campaign Contacts (recipients) ───────────────────────────────────────────
-- status: pending | sent | delivered | read | failed | opted_out
CREATE TABLE campaign_contacts (
    id              BIGSERIAL PRIMARY KEY,
    campaign_id     BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    related_type    VARCHAR(20),                    -- Lead | Contact
    related_id      BIGINT,
    phone_number    VARCHAR(30)  NOT NULL,
    name            VARCHAR(200),
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    wa_message_id   VARCHAR(200),
    sent_at         TIMESTAMP,
    delivered_at    TIMESTAMP,
    read_at         TIMESTAMP,
    failed_reason   VARCHAR(300),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_campaign_contacts_campaign ON campaign_contacts(campaign_id);
CREATE INDEX idx_campaign_contacts_status   ON campaign_contacts(campaign_id, status);
