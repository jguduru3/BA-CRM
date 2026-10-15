-- ── Attachments — files/images attached to any CRM or workspace record ──────
-- Storage-backend agnostic: storage_key is a relative path under the active
-- FileStorageService backend (local disk by default, Azure Blob container
-- when AZURE_STORAGE_CONNECTION_STRING is configured — see FileStorageService).
CREATE TABLE attachments (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organizations(id),
    related_type     VARCHAR(30)  NOT NULL,   -- Lead | Opportunity | Contact | Company | Document | Note | DatabaseRecord | ...
    related_id       BIGINT       NOT NULL,
    file_name        VARCHAR(400) NOT NULL,
    content_type     VARCHAR(150),
    size_bytes       BIGINT       NOT NULL DEFAULT 0,
    storage_backend  VARCHAR(20)  NOT NULL DEFAULT 'local', -- local | azure_blob
    storage_key      VARCHAR(600) NOT NULL,
    uploaded_by      BIGINT REFERENCES users(id),
    created_at       TIMESTAMP  NOT NULL DEFAULT now()
);
CREATE INDEX idx_attachments_related ON attachments(organization_id, related_type, related_id);

-- ── Whiteboards — a Jamboard-style freeform planning/brainstorming canvas ───
CREATE TABLE whiteboards (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT       NOT NULL REFERENCES organizations(id),
    name             VARCHAR(200) NOT NULL,
    description      TEXT,
    created_by       BIGINT REFERENCES users(id),
    created_at       TIMESTAMP  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP  NOT NULL DEFAULT now()
);

CREATE TABLE whiteboard_items (
    id               BIGSERIAL PRIMARY KEY,
    board_id         BIGINT       NOT NULL REFERENCES whiteboards(id) ON DELETE CASCADE,
    organization_id  BIGINT       NOT NULL REFERENCES organizations(id),
    type             VARCHAR(20)  NOT NULL DEFAULT 'sticky', -- sticky | text | shape | image | connector
    x                DOUBLE PRECISION NOT NULL DEFAULT 0,
    y                DOUBLE PRECISION NOT NULL DEFAULT 0,
    w                DOUBLE PRECISION NOT NULL DEFAULT 200,
    h                DOUBLE PRECISION NOT NULL DEFAULT 140,
    rotation         DOUBLE PRECISION NOT NULL DEFAULT 0,
    z_index          INT          NOT NULL DEFAULT 0,
    content          TEXT,
    color            VARCHAR(20)  DEFAULT '#ffe066',
    attachment_id    BIGINT REFERENCES attachments(id),
    created_by       BIGINT REFERENCES users(id),
    created_at       TIMESTAMP  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP  NOT NULL DEFAULT now()
);
CREATE INDEX idx_whiteboard_items_board ON whiteboard_items(board_id);

-- ── Contact categorization for WhatsApp/campaign audience building ─────────
-- Mirrors Lead.industry/geography so Leads and Contacts share the same
-- segmentation vocabulary across the funnel.
ALTER TABLE contacts ADD COLUMN industry            VARCHAR(100);
ALTER TABLE contacts ADD COLUMN country              VARCHAR(100);
ALTER TABLE contacts ADD COLUMN state                VARCHAR(100);
ALTER TABLE contacts ADD COLUMN nature_of_business    VARCHAR(150);

ALTER TABLE contact_categories ADD COLUMN industry  VARCHAR(100);
ALTER TABLE contact_categories ADD COLUMN country    VARCHAR(100);
ALTER TABLE contact_categories ADD COLUMN state      VARCHAR(100);

CREATE INDEX idx_contacts_industry ON contacts(organization_id, industry);
CREATE INDEX idx_contacts_country  ON contacts(organization_id, country);
