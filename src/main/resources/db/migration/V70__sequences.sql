-- V70: multi-channel sequences (Close semantics) — sequences, ordered steps,
-- enrollments with response-aware exit / per-channel skip, an at-most-once
-- step-execution record and an enrollment history.
-- Portable across PostgreSQL 16 and H2 (MODE=PostgreSQL): plain TIMESTAMP,
-- no partial indexes, no Postgres-only functions. Additive only.

CREATE TABLE sequences (
    id                  BIGSERIAL PRIMARY KEY,
    organization_id     BIGINT        NOT NULL REFERENCES organizations(id),
    name                VARCHAR(200)  NOT NULL,
    description         VARCHAR(1000),
    active              BOOLEAN       DEFAULT TRUE NOT NULL,
    email_account_id    BIGINT,                       -- NULL = the enrollment owner's own mailbox
    whatsapp_account_id BIGINT,                       -- NULL = the org's first active sender
    created_by          BIGINT,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    deleted_at          TIMESTAMP,
    deleted_by          BIGINT,
    version             BIGINT        DEFAULT 0 NOT NULL
);
CREATE INDEX idx_sequences_org ON sequences(organization_id, deleted_at);

-- delay_* is measured from the previous step (or from enrollment for the first step).
CREATE TABLE sequence_steps (
    id                   BIGSERIAL PRIMARY KEY,
    sequence_id          BIGINT        NOT NULL REFERENCES sequences(id),
    step_index           INT           NOT NULL,
    channel              VARCHAR(20)   NOT NULL,      -- EMAIL | WHATSAPP | TASK
    delay_days           INT           DEFAULT 0 NOT NULL,
    delay_hours          INT           DEFAULT 0 NOT NULL,
    subject              VARCHAR(300),                -- email subject / task title
    body                 TEXT,                        -- email body / task notes
    wa_template_name     VARCHAR(100),
    wa_template_language VARCHAR(10),
    wa_template_params   TEXT,                        -- JSON array of body parameter strings
    CONSTRAINT uq_sequence_steps_index UNIQUE (sequence_id, step_index)
);

-- active_key = 'sequenceId:Type:recordId' while ACTIVE or PAUSED, NULL otherwise:
-- a portable "one live enrollment per (sequence, record)" unique constraint
-- (NULLs never collide), instead of a partial index.
CREATE TABLE sequence_enrollments (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT        NOT NULL REFERENCES organizations(id),
    sequence_id      BIGINT        NOT NULL REFERENCES sequences(id),
    related_type     VARCHAR(20)   NOT NULL,          -- Lead | Contact
    related_id       BIGINT        NOT NULL,
    owner_user_id    BIGINT,                          -- sender / task assignee
    enrolled_by      BIGINT,
    status           VARCHAR(20)   NOT NULL,          -- ACTIVE | PAUSED | RESPONDED | COMPLETED | STOPPED | FAILED
    current_step     INT           DEFAULT 0 NOT NULL,
    next_run_at      TIMESTAMP,
    claimed_at       TIMESTAMP,
    skipped_channels VARCHAR(100),                    -- comma-separated channels skipped after a failure
    contact_email    VARCHAR(320),                    -- lower-cased snapshot, refreshed every step
    contact_phone    VARCHAR(20),                     -- E.164 snapshot, refreshed every step
    active_key       VARCHAR(100),
    stop_reason      VARCHAR(300),
    responded_at     TIMESTAMP,
    completed_at     TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL,
    CONSTRAINT uq_sequence_enrollments_active UNIQUE (active_key)
);
CREATE INDEX idx_seq_enrollments_due    ON sequence_enrollments(status, next_run_at);
CREATE INDEX idx_seq_enrollments_seq    ON sequence_enrollments(sequence_id, status);
CREATE INDEX idx_seq_enrollments_record ON sequence_enrollments(organization_id, related_type, related_id);
CREATE INDEX idx_seq_enrollments_email  ON sequence_enrollments(organization_id, contact_email);
CREATE INDEX idx_seq_enrollments_phone  ON sequence_enrollments(organization_id, contact_phone);

-- One row per (enrollment, step): the at-most-once send record.
CREATE TABLE sequence_step_executions (
    id                  BIGSERIAL PRIMARY KEY,
    enrollment_id       BIGINT        NOT NULL REFERENCES sequence_enrollments(id),
    organization_id     BIGINT        NOT NULL,
    step_index          INT           NOT NULL,
    step_id             BIGINT,
    channel             VARCHAR(20)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,       -- PENDING | SENT | SKIPPED | FAILED
    detail              VARCHAR(500),
    wa_message_id       VARCHAR(200),
    whatsapp_message_id BIGINT,
    task_id             BIGINT,
    created_at          TIMESTAMP     NOT NULL,
    completed_at        TIMESTAMP,
    CONSTRAINT uq_sequence_step_executions UNIQUE (enrollment_id, step_index)
);
CREATE INDEX idx_seq_exec_wa ON sequence_step_executions(organization_id, wa_message_id);

CREATE TABLE sequence_enrollment_events (
    id              BIGSERIAL PRIMARY KEY,
    enrollment_id   BIGINT        NOT NULL REFERENCES sequence_enrollments(id),
    organization_id BIGINT        NOT NULL,
    event_type      VARCHAR(30)   NOT NULL,
    step_index      INT,
    channel         VARCHAR(20),
    detail          VARCHAR(500),
    actor_id        BIGINT,
    created_at      TIMESTAMP     NOT NULL
);
CREATE INDEX idx_seq_events_enrollment ON sequence_enrollment_events(enrollment_id, created_at);
