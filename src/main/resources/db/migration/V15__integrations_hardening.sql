-- V15: integrations hardening (campaigns / WhatsApp / AI automations / email)
-- Portable across PostgreSQL and H2 (PostgreSQL mode): no partial indexes, no TIMESTAMPTZ.
--
-- Secret columns (whatsapp_accounts.access_token, email_accounts.smtp_password_encrypted,
-- ai_automations.api_key_encrypted, org_settings.ai_api_key) are already TEXT, so the
-- AES-GCM "enc:v1:" values written by EncryptedStringConverter fit without widening.
-- Existing plaintext values are read back as-is and encrypted on next save.

-- 1. WhatsApp phone_number_id must be globally unique: inbound webhooks resolve the
--    owning organization from it. Neutralise pre-existing duplicates first (keep the
--    oldest row, deactivate and rename the others) so the constraint can be created.
UPDATE whatsapp_accounts
   SET phone_number_id = 'dup-' || CAST(id AS VARCHAR(20)) || '-' || phone_number_id,
       is_active = FALSE
 WHERE id NOT IN (SELECT keep_id FROM (SELECT MIN(id) AS keep_id
                                         FROM whatsapp_accounts
                                        GROUP BY phone_number_id) k);

ALTER TABLE whatsapp_accounts
    ADD CONSTRAINT uq_whatsapp_accounts_phone_number_id UNIQUE (phone_number_id);

-- 2. Verify-token lookup for the GET webhook handshake (replaces findAll()).
CREATE INDEX idx_wa_accounts_verify_token ON whatsapp_accounts(webhook_verify_token);

-- 3. Inbound de-duplication / status updates look messages up by (wa_message_id, org).
CREATE INDEX idx_wa_messages_wamid_org ON whatsapp_messages(wa_message_id, organization_id);

-- 4. Campaign template language (was hard-coded en_US).
ALTER TABLE campaigns ADD COLUMN template_language VARCHAR(20) DEFAULT 'en_US';

-- 5. Webhook status events update campaign recipients by wa_message_id.
CREATE INDEX idx_campaign_contacts_wamid ON campaign_contacts(wa_message_id);

-- 6. Scheduler scans due scheduled campaigns.
CREATE INDEX idx_campaigns_status_scheduled ON campaigns(status, scheduled_at);
