-- V7__phase2_custom_databases_documents.sql
-- Phase 2: Notion-style custom databases (typed properties, multiple views) + Documents
-- Architecture:
--   custom_databases   → the "database" definition (like a Notion database)
--   database_properties → typed field definitions (schema) for a database
--   database_records   → rows in the database
--   property_values    → one row per (record, property) pair, typed by columns
--   database_views     → saved views (Table / Kanban / Calendar / Timeline)
--   documents          → rich-text documents linked to any CRM record

-- ── Custom Databases ─────────────────────────────────────────────────────────
CREATE TABLE custom_databases (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(200) NOT NULL,
    icon            VARCHAR(100),       -- emoji or Lucide icon name
    color           VARCHAR(20),
    description     TEXT,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_custom_databases_org ON custom_databases(organization_id);

-- ── Database Properties (field schema) ──────────────────────────────────────
-- type values: text | number | date | select | multi_select | relation |
--              checkbox | url | email | phone | file | formula
CREATE TABLE database_properties (
    id              BIGSERIAL PRIMARY KEY,
    database_id     BIGINT NOT NULL REFERENCES custom_databases(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(100) NOT NULL,
    type            VARCHAR(20)  NOT NULL DEFAULT 'text',
    -- JSON array of {id, label, color} for select/multi_select types
    options_json    TEXT,
    -- for relation type: which other database this field points to
    relation_db_id  BIGINT REFERENCES custom_databases(id),
    required        BOOLEAN NOT NULL DEFAULT FALSE,
    position        INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_db_properties_database ON database_properties(database_id);

-- ── Database Records (rows) ──────────────────────────────────────────────────
CREATE TABLE database_records (
    id              BIGSERIAL PRIMARY KEY,
    database_id     BIGINT NOT NULL REFERENCES custom_databases(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    owner_id        BIGINT REFERENCES users(id),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMP,
    deleted_by      BIGINT REFERENCES users(id)
);
CREATE INDEX idx_db_records_database ON database_records(database_id);
CREATE INDEX idx_db_records_org      ON database_records(organization_id);
CREATE INDEX idx_db_records_deleted  ON database_records(deleted_at);

-- ── Property Values (cell data) ──────────────────────────────────────────────
-- One row per (record × property). Type-correct storage:
--   text / url / email / phone / formula → text_value
--   number                               → number_value
--   date                                 → date_value
--   checkbox                             → bool_value
--   select / multi_select / relation / file → json_value (JSON string)
CREATE TABLE property_values (
    id              BIGSERIAL PRIMARY KEY,
    record_id       BIGINT NOT NULL REFERENCES database_records(id) ON DELETE CASCADE,
    property_id     BIGINT NOT NULL REFERENCES database_properties(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    text_value      TEXT,
    number_value    DECIMAL(20,4),
    date_value      TIMESTAMP,
    bool_value      BOOLEAN,
    json_value      TEXT,   -- JSON for multi-value types (multi_select, relation, file)
    UNIQUE (record_id, property_id)
);
CREATE INDEX idx_property_values_record   ON property_values(record_id);
CREATE INDEX idx_property_values_property ON property_values(property_id);

-- ── Database Views ────────────────────────────────────────────────────────────
-- type: table | kanban | calendar | timeline
-- config_json holds: { filters, sorts, groupBy, visibleProperties, kanbanProperty, calendarProperty }
CREATE TABLE database_views (
    id              BIGSERIAL PRIMARY KEY,
    database_id     BIGINT NOT NULL REFERENCES custom_databases(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(100) NOT NULL,
    type            VARCHAR(20)  NOT NULL DEFAULT 'table',
    config_json     TEXT,
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    position        INT NOT NULL DEFAULT 0,
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_db_views_database ON database_views(database_id);

-- ── Documents (rich-text, linked to any CRM record) ──────────────────────────
-- content_json stores ProseMirror/Tiptap JSON.
-- related_type / related_id link to Lead, Opportunity, Contact, Company, or DatabaseRecord.
CREATE TABLE documents (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    title           VARCHAR(300) NOT NULL,
    content_json    TEXT,
    related_type    VARCHAR(30),  -- Lead | Opportunity | Contact | Company | DatabaseRecord | null
    related_id      BIGINT,
    owner_id        BIGINT REFERENCES users(id),
    created_by      BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMP,
    deleted_by      BIGINT REFERENCES users(id)
);
CREATE INDEX idx_documents_org     ON documents(organization_id);
CREATE INDEX idx_documents_related ON documents(related_type, related_id);
CREATE INDEX idx_documents_deleted ON documents(deleted_at);
