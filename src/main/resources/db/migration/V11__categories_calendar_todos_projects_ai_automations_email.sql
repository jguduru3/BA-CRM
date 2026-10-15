-- V11: Contact Categories, Calendar, To-Do Lists, Reminders, Projects, AI Automations, Email Integration

-- 1. Contact categories (predefined system + custom per org)
CREATE TABLE contact_categories (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    color           VARCHAR(20),
    icon            VARCHAR(50),
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by      BIGINT,
    created_at      TIMESTAMP,
    CONSTRAINT uq_contact_category UNIQUE (organization_id, name)
);

-- 2. Calendar events
CREATE TABLE calendar_events (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL,
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    start_at        TIMESTAMP    NOT NULL,
    end_at          TIMESTAMP,
    all_day         BOOLEAN      NOT NULL DEFAULT FALSE,
    event_type      VARCHAR(30)  NOT NULL DEFAULT 'event',
    related_type    VARCHAR(50),
    related_id      BIGINT,
    color_hex       VARCHAR(10),
    location        VARCHAR(300),
    recurrence_rule VARCHAR(200),
    owner_id        BIGINT       NOT NULL,
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

-- 3. Calendar event attendees
CREATE TABLE calendar_attendees (
    id       BIGSERIAL PRIMARY KEY,
    event_id BIGINT       NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
    user_id  BIGINT,
    email    VARCHAR(200),
    name     VARCHAR(100),
    status   VARCHAR(20) DEFAULT 'pending'
);

-- 4. External calendar integrations (Google / Outlook OAuth)
CREATE TABLE calendar_integrations (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT      NOT NULL,
    user_id          BIGINT      NOT NULL,
    provider         VARCHAR(30) NOT NULL,
    access_token     TEXT,
    refresh_token    TEXT,
    token_expires_at TIMESTAMP,
    calendar_id      VARCHAR(200),
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    synced_at        TIMESTAMP,
    created_at       TIMESTAMP,
    CONSTRAINT uq_calendar_integration UNIQUE (user_id, provider)
);

-- 5. Personal / shared to-do lists
CREATE TABLE todo_lists (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    color           VARCHAR(20),
    icon            VARCHAR(50),
    is_shared       BOOLEAN NOT NULL DEFAULT FALSE,
    owner_id        BIGINT  NOT NULL,
    position        INT     NOT NULL DEFAULT 0,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

-- 6. To-do items (supports sub-tasks via parent_id)
CREATE TABLE todo_items (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL,
    list_id         BIGINT       REFERENCES todo_lists(id) ON DELETE CASCADE,
    title           VARCHAR(500) NOT NULL,
    notes           TEXT,
    is_done         BOOLEAN NOT NULL DEFAULT FALSE,
    is_pinned       BOOLEAN NOT NULL DEFAULT FALSE,
    priority        VARCHAR(20)  NOT NULL DEFAULT 'normal',
    due_at          TIMESTAMP,
    done_at         TIMESTAMP,
    position        INT          NOT NULL DEFAULT 0,
    parent_id       BIGINT       REFERENCES todo_items(id),
    related_type    VARCHAR(50),
    related_id      BIGINT,
    created_by      BIGINT NOT NULL,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

-- 7. Reminders (fires push + in-app notification at remind_at)
CREATE TABLE reminders (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    title           VARCHAR(200) NOT NULL,
    body            TEXT,
    remind_at       TIMESTAMP    NOT NULL,
    is_fired        BOOLEAN      NOT NULL DEFAULT FALSE,
    fired_at        TIMESTAMP,
    related_type    VARCHAR(50),
    related_id      BIGINT,
    created_at      TIMESTAMP
);

-- 8. Projects
CREATE TABLE projects (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    status          VARCHAR(30)  NOT NULL DEFAULT 'active',
    priority        VARCHAR(20)  NOT NULL DEFAULT 'normal',
    owner_id        BIGINT       NOT NULL,
    start_date      DATE,
    due_date        DATE,
    completed_at    TIMESTAMP,
    budget          BIGINT,
    tags            TEXT,
    cover_color     VARCHAR(20),
    icon            VARCHAR(50),
    created_by      BIGINT NOT NULL,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    deleted_at      TIMESTAMP,
    deleted_by      BIGINT
);

-- 9. Project members
CREATE TABLE project_members (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    role            VARCHAR(30) NOT NULL DEFAULT 'member',
    joined_at       TIMESTAMP,
    CONSTRAINT uq_project_member UNIQUE (project_id, user_id)
);

-- 10. Project milestones
CREATE TABLE project_milestones (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    due_date        DATE,
    is_completed    BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at    TIMESTAMP,
    position        INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP
);

-- 11. AI automation definitions (trigger + action pairs; runs on schedule/event/manual)
CREATE TABLE ai_automations (
    id                  BIGSERIAL PRIMARY KEY,
    organization_id     BIGINT       NOT NULL,
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    trigger_type        VARCHAR(50)  NOT NULL,
    trigger_config_json TEXT,
    action_type         VARCHAR(50)  NOT NULL,
    action_config_json  TEXT,
    api_provider        VARCHAR(30)  DEFAULT 'anthropic',
    api_key_encrypted   TEXT,
    model_id            VARCHAR(100) DEFAULT 'claude-opus-5-5',
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    run_count           INT     NOT NULL DEFAULT 0,
    last_run_at         TIMESTAMP,
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP
);

-- 12. AI automation run log
CREATE TABLE ai_automation_runs (
    id                   BIGSERIAL PRIMARY KEY,
    automation_id        BIGINT NOT NULL REFERENCES ai_automations(id) ON DELETE CASCADE,
    organization_id      BIGINT NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'running',
    trigger_payload_json TEXT,
    resolved_input_json  TEXT,
    output_text          TEXT,
    action_result_json   TEXT,
    error_message        TEXT,
    started_at           TIMESTAMP NOT NULL,
    completed_at         TIMESTAMP,
    tokens_used          INT,
    triggered_by         BIGINT
);

-- 13. Email accounts (SMTP send + IMAP read for automation)
CREATE TABLE email_accounts (
    id                      BIGSERIAL PRIMARY KEY,
    organization_id         BIGINT       NOT NULL,
    user_id                 BIGINT       NOT NULL,
    email_address           VARCHAR(200) NOT NULL,
    display_name            VARCHAR(100),
    provider                VARCHAR(30)  DEFAULT 'smtp_imap',
    smtp_host               VARCHAR(200),
    smtp_port               INT DEFAULT 587,
    smtp_user               VARCHAR(200),
    smtp_password_encrypted TEXT,
    smtp_tls                BOOLEAN NOT NULL DEFAULT TRUE,
    imap_host               VARCHAR(200),
    imap_port               INT DEFAULT 993,
    imap_ssl                BOOLEAN NOT NULL DEFAULT TRUE,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at          TIMESTAMP,
    created_at              TIMESTAMP,
    CONSTRAINT uq_email_account UNIQUE (organization_id, email_address)
);

-- Link contacts to categories; link tasks to projects
ALTER TABLE contacts ADD COLUMN IF NOT EXISTS category_id BIGINT;
ALTER TABLE tasks    ADD COLUMN IF NOT EXISTS project_id  BIGINT;

-- Indexes for common lookups
CREATE INDEX idx_calendar_events_org_start ON calendar_events(organization_id, start_at);
CREATE INDEX idx_todo_items_list           ON todo_items(list_id, position);
-- Plain (non-partial) indexes: H2 — the local-dev profile's engine — doesn't
-- support Postgres's partial-index WHERE clause, and a full index on these
-- small reference columns costs little on either engine.
CREATE INDEX idx_reminders_fire            ON reminders(remind_at);
CREATE INDEX idx_projects_org              ON projects(organization_id);
CREATE INDEX idx_ai_automations_org        ON ai_automations(organization_id);
CREATE INDEX idx_ai_runs_automation        ON ai_automation_runs(automation_id);
