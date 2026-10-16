-- V81: scheduled IMAP inbox polling per mailbox ("Watch inbox for replies").
-- last_polled_uid / last_polled_uid_validity: IMAP UID high-water mark, so each
-- poll reads only messages that arrived since the previous one (a UIDVALIDITY
-- change resets the baseline instead of re-reading the mailbox).
ALTER TABLE email_accounts ADD COLUMN poll_inbox BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE email_accounts ADD COLUMN last_polled_uid BIGINT;
ALTER TABLE email_accounts ADD COLUMN last_polled_uid_validity BIGINT;
ALTER TABLE email_accounts ADD COLUMN last_polled_at TIMESTAMP;
ALTER TABLE email_accounts ADD COLUMN last_poll_error VARCHAR(300);
