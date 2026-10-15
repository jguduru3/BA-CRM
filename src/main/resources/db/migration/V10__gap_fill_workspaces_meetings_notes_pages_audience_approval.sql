-- V10: Gap-fill — workspaces, meetings (standalone), notes (standalone),
--       record pages, campaign audience builder, campaign approval state

-- ── 1. Workspaces ─────────────────────────────────────────────────────────────
-- Sub-org workspaces; each org can have multiple named workspaces grouping
-- teams, databases and views. Teams can optionally belong to a workspace.
CREATE TABLE workspaces (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    icon            VARCHAR(50)  DEFAULT 'grid',
    color           VARCHAR(20)  DEFAULT '#29ABE2',
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_workspaces_org ON workspaces(organization_id);

-- Optional: link teams to a workspace
ALTER TABLE teams ADD COLUMN IF NOT EXISTS workspace_id BIGINT REFERENCES workspaces(id);

-- ── 2. Meetings (standalone entity) ───────────────────────────────────────────
-- Dedicated meeting records distinct from the Activity timeline entry.
-- A meeting can be linked to any CRM record via related_type + related_id.
-- outcome: scheduled | completed | cancelled | no_show | rescheduled
CREATE TABLE meetings (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    title           VARCHAR(300) NOT NULL,
    description     TEXT,
    location        VARCHAR(500),
    meeting_url     VARCHAR(500),               -- Google Meet / Zoom / Teams link
    status          VARCHAR(20)  NOT NULL DEFAULT 'scheduled',
    start_at        TIMESTAMP    NOT NULL,
    end_at          TIMESTAMP,
    duration_minutes INT,
    organizer_id    BIGINT REFERENCES users(id),
    related_type    VARCHAR(30),                -- Lead | Opportunity | Contact | Company
    related_id      BIGINT,
    notes           TEXT,
    outcome         TEXT,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMP,
    deleted_by      BIGINT REFERENCES users(id)
);
CREATE INDEX idx_meetings_org        ON meetings(organization_id);
CREATE INDEX idx_meetings_related    ON meetings(related_type, related_id);
CREATE INDEX idx_meetings_organizer  ON meetings(organizer_id);

-- Meeting attendees
CREATE TABLE meeting_attendees (
    id          BIGSERIAL PRIMARY KEY,
    meeting_id  BIGINT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    user_id     BIGINT REFERENCES users(id),
    email       VARCHAR(200),                   -- external attendee
    name        VARCHAR(200),
    rsvp        VARCHAR(20) DEFAULT 'pending',  -- pending | accepted | declined | tentative
    joined_at   TIMESTAMP
);
CREATE INDEX idx_meeting_attendees_meeting ON meeting_attendees(meeting_id);

-- ── 3. Notes (standalone entity) ──────────────────────────────────────────────
-- Rich text notes linked to any CRM record. Separate from the free-text
-- "notes" column on each entity — these are standalone, timestamped, authorable.
CREATE TABLE notes (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    title           VARCHAR(300),
    body            TEXT NOT NULL,
    related_type    VARCHAR(30),                -- Lead | Opportunity | Contact | Company | Meeting
    related_id      BIGINT,
    is_pinned       BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMP,
    deleted_by      BIGINT REFERENCES users(id)
);
CREATE INDEX idx_notes_org     ON notes(organization_id);
CREATE INDEX idx_notes_related ON notes(related_type, related_id);

-- ── 4. Record Pages ────────────────────────────────────────────────────────────
-- Full workspace "page" per CRM record. Each page has an ordered list of
-- content blocks (text, embeds, checklists, dividers, property tables, etc.).
-- block_type: text | heading | checklist | divider | embed | property_table | ai_summary
CREATE TABLE record_pages (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    related_type    VARCHAR(30) NOT NULL,       -- Lead | Opportunity | Contact | Company | CustomRecord
    related_id      BIGINT NOT NULL,
    cover_color     VARCHAR(20),
    icon            VARCHAR(50),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (organization_id, related_type, related_id)
);
CREATE INDEX idx_record_pages_org     ON record_pages(organization_id);
CREATE INDEX idx_record_pages_related ON record_pages(related_type, related_id);

CREATE TABLE page_blocks (
    id          BIGSERIAL PRIMARY KEY,
    page_id     BIGINT NOT NULL REFERENCES record_pages(id) ON DELETE CASCADE,
    block_type  VARCHAR(30) NOT NULL DEFAULT 'text',
    content     TEXT,
    position    INT NOT NULL DEFAULT 0,
    is_checked  BOOLEAN DEFAULT FALSE,          -- for checklist blocks
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_page_blocks_page ON page_blocks(page_id);

-- ── 5. Campaign Audience Builder ──────────────────────────────────────────────
-- Stores filter criteria for a campaign audience. On launch, the server
-- evaluates criteria_json against leads/contacts to build the recipient list.
-- criteria_json example:
--   { "entity": "lead",
--     "filters": [
--       { "field": "industry", "op": "in", "values": ["Technology","Finance"] },
--       { "field": "stage",    "op": "eq", "value":  "Qualified" },
--       { "field": "value",    "op": "gte","value":  100000 }
--     ]
--   }
CREATE TABLE campaign_audiences (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    campaign_id     BIGINT NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    name            VARCHAR(200),
    criteria_json   TEXT NOT NULL,              -- filter spec (JSON)
    estimated_size  INT  DEFAULT 0,
    last_evaluated  TIMESTAMP,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_campaign_audiences_campaign ON campaign_audiences(campaign_id);

-- ── 6. Campaign approval state ────────────────────────────────────────────────
-- Adds pending_approval status + approval tracking columns to campaigns.
-- Updated status domain: draft | pending_approval | approved | scheduled | running | paused | completed | cancelled
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS approved_by      BIGINT REFERENCES users(id);
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS approved_at      TIMESTAMP;
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS submitted_at     TIMESTAMP;  -- when sent for approval
ALTER TABLE campaigns ADD COLUMN IF NOT EXISTS audience_criteria_json TEXT; -- inline audience (alternative to audience table)
