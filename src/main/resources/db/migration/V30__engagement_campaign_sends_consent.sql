-- V30: engagement hardening — campaign send records, WhatsApp consent, normalized
-- phone columns, optimistic locking, automation run duration + triggers.
-- Portable across PostgreSQL 16 and H2 (MODE=PostgreSQL): plain TIMESTAMP, no partial
-- indexes, no Postgres-only functions. Nothing is dropped or deleted.

-- ── 1. Campaigns: optimistic locking + canonical status names ─────────────────
ALTER TABLE campaigns ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

-- Status flow is now draft → submitted → approved/rejected → scheduled → sending →
-- completed (+ paused, cancelled). Map the two legacy names (non-destructive rename).
UPDATE campaigns SET status = 'sending'   WHERE status = 'running';
UPDATE campaigns SET status = 'submitted' WHERE status = 'pending_approval';

-- ── 2. Campaign recipients = per-recipient send records ───────────────────────
-- normalized_phone: E.164 with a leading '+'. Unique per campaign so the same
-- number can never be messaged twice by one campaign. claimed_at lets the
-- dispatcher reset 'sending' claims abandoned by a crashed worker.
ALTER TABLE campaign_contacts ADD COLUMN normalized_phone VARCHAR(20);
ALTER TABLE campaign_contacts ADD COLUMN claimed_at TIMESTAMP;
ALTER TABLE campaign_contacts ADD COLUMN updated_at TIMESTAMP;

UPDATE campaign_contacts
   SET normalized_phone = CASE WHEN phone_number LIKE '+%' THEN phone_number ELSE '+' || phone_number END;

-- Existing duplicates (same campaign + number) keep their row but lose the
-- normalized key (NULLs never collide), and are skipped if not yet sent.
UPDATE campaign_contacts
   SET normalized_phone = NULL,
       status = CASE WHEN status = 'pending' THEN 'skipped' ELSE status END
 WHERE id NOT IN (SELECT keep_id FROM (SELECT MIN(id) AS keep_id
                                         FROM campaign_contacts
                                        GROUP BY campaign_id, normalized_phone) k);

ALTER TABLE campaign_contacts
    ADD CONSTRAINT uq_campaign_contacts_campaign_phone UNIQUE (campaign_id, normalized_phone);
CREATE INDEX idx_campaign_contacts_claim ON campaign_contacts(status, claimed_at);
CREATE INDEX idx_campaign_contacts_phone ON campaign_contacts(organization_id, normalized_phone);

-- ── 3. WhatsApp messages: counterparty phone in E.164 for indexed thread lookups ─
ALTER TABLE whatsapp_messages ADD COLUMN normalized_phone VARCHAR(20);
ALTER TABLE whatsapp_messages ADD COLUMN delivered_at TIMESTAMP;
ALTER TABLE whatsapp_messages ADD COLUMN read_at TIMESTAMP;

UPDATE whatsapp_messages
   SET normalized_phone = CASE
         WHEN direction = 'inbound' AND from_number IS NOT NULL
              THEN CASE WHEN from_number LIKE '+%' THEN from_number ELSE '+' || from_number END
         WHEN to_number IS NOT NULL
              THEN CASE WHEN to_number LIKE '+%' THEN to_number ELSE '+' || to_number END
         ELSE NULL END;

CREATE INDEX idx_wa_messages_org_phone ON whatsapp_messages(organization_id, normalized_phone, created_at);

-- ── 4. WhatsApp consent (opt-in / opt-out) per org + number, with history ─────
CREATE TABLE whatsapp_consents (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organizations(id),
    normalized_phone VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,          -- opted_in | opted_out
    source           VARCHAR(30)  NOT NULL,          -- inbound_keyword | manual | import
    reason           VARCHAR(500),
    updated_by       BIGINT REFERENCES users(id),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT uq_whatsapp_consents_org_phone UNIQUE (organization_id, normalized_phone)
);

CREATE TABLE whatsapp_consent_events (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organizations(id),
    normalized_phone VARCHAR(20)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    source           VARCHAR(30)  NOT NULL,
    reason           VARCHAR(500),
    actor_user_id    BIGINT REFERENCES users(id),
    message_id       BIGINT,
    created_at       TIMESTAMP    NOT NULL
);
CREATE INDEX idx_wa_consent_events_phone ON whatsapp_consent_events(organization_id, normalized_phone, created_at);

-- ── 5. Automations: run duration, cron bookkeeping, webhook trigger secret ────
ALTER TABLE ai_automation_runs ADD COLUMN duration_ms BIGINT;
ALTER TABLE ai_automation_runs ADD COLUMN trigger_source VARCHAR(30);
CREATE INDEX idx_ai_runs_automation_started ON ai_automation_runs(automation_id, started_at);

ALTER TABLE ai_automations ADD COLUMN schedule_last_fired_at TIMESTAMP;
-- HMAC secret for the inbound webhook trigger; AES-GCM encrypted by the app.
ALTER TABLE ai_automations ADD COLUMN webhook_secret TEXT;
CREATE INDEX idx_ai_automations_trigger ON ai_automations(trigger_type, is_active);

-- ── 6. Push device tokens: one row per (user, device token) already; add lookup ──
CREATE INDEX idx_device_tokens_token ON device_tokens(token);
