-- V1__init.sql
-- Phase 1 - Internal CRM baseline schema.
-- Every tenant-scoped table carries organization_id per the spec's
-- multi-tenant guardrail (enforced at the JPA/repository layer from the
-- first commit, not bolted on later).

CREATE TABLE organizations (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    slug          VARCHAR(100) UNIQUE,
    plan          VARCHAR(20) NOT NULL DEFAULT 'starter',
    industry      VARCHAR(100),
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(120) NOT NULL,
    email           VARCHAR(200) NOT NULL UNIQUE,
    password        VARCHAR(200) NOT NULL,
    role            VARCHAR(30) NOT NULL DEFAULT 'SALES_REP',
    initials        VARCHAR(4),
    color           VARCHAR(20) DEFAULT '#0078d4',
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_org ON users(organization_id);

CREATE TABLE departments (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    head_id         BIGINT REFERENCES users(id),
    parent_id       BIGINT REFERENCES departments(id),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_departments_org ON departments(organization_id);

CREATE TABLE teams (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    leader_id       BIGINT REFERENCES users(id),
    department_id   BIGINT REFERENCES departments(id),
    color           VARCHAR(20) DEFAULT '#0078d4',
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_teams_org ON teams(organization_id);

CREATE TABLE team_members (
    id       BIGSERIAL PRIMARY KEY,
    team_id  BIGINT NOT NULL REFERENCES teams(id),
    user_id  BIGINT NOT NULL REFERENCES users(id),
    role     VARCHAR(20) NOT NULL DEFAULT 'MEMBER'
);

CREATE TABLE companies (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(200) NOT NULL,
    industry        VARCHAR(100),
    size            VARCHAR(20),
    website         VARCHAR(300),
    phone           VARCHAR(30),
    email           VARCHAR(200),
    location        VARCHAR(200),
    revenue         BIGINT DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'Prospect',
    notes           TEXT,
    owner_id        BIGINT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_companies_org ON companies(organization_id);

CREATE TABLE contacts (
    id                BIGSERIAL PRIMARY KEY,
    organization_id   BIGINT NOT NULL REFERENCES organizations(id),
    name              VARCHAR(120) NOT NULL,
    title             VARCHAR(120),
    email             VARCHAR(200),
    phone             VARCHAR(30),
    company_id        BIGINT REFERENCES companies(id),
    owner_id          BIGINT REFERENCES users(id),
    status            VARCHAR(20) NOT NULL DEFAULT 'Active',
    source            VARCHAR(60),
    notes             TEXT,
    tags              VARCHAR(300),
    linkedin          VARCHAR(300),
    last_contacted_at TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_contacts_org ON contacts(organization_id);

-- Leads: core CRM object + the new data-feed / pipeline-tracking fields.
CREATE TABLE leads (
    id                BIGSERIAL PRIMARY KEY,
    organization_id   BIGINT NOT NULL REFERENCES organizations(id),
    name              VARCHAR(200) NOT NULL,
    email             VARCHAR(200),
    phone             VARCHAR(30),
    company_id        BIGINT REFERENCES companies(id),
    owner_id          BIGINT REFERENCES users(id),
    stage             VARCHAR(20) NOT NULL DEFAULT 'New',      -- lead status
    source            VARCHAR(30) NOT NULL DEFAULT 'Direct',   -- lead source
    priority          VARCHAR(10) NOT NULL DEFAULT 'Medium',
    deal_value        BIGINT DEFAULT 0,                         -- named deal_value, not value: VALUE is a reserved word in H2
    notes             TEXT,                                    -- lead notes
    tags              VARCHAR(300),
    converted_at      TIMESTAMP,
    converted_opp_id  BIGINT,
    follow_up_at      TIMESTAMP,

    -- NEW — data-feed intake fields
    address           VARCHAR(400),
    business_name     VARCHAR(200),
    category          VARCHAR(30),     -- Industry | In-house | Other services
    lead_type         VARCHAR(30),     -- Inbound | Outbound | Referral | Partner

    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_leads_org ON leads(organization_id);
CREATE INDEX idx_leads_stage ON leads(stage);
CREATE INDEX idx_leads_source ON leads(source);
CREATE INDEX idx_leads_lead_type ON leads(lead_type);
CREATE INDEX idx_leads_category ON leads(category);

CREATE TABLE opportunities (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name            VARCHAR(200) NOT NULL,
    company_id      BIGINT REFERENCES companies(id),
    contact_id      BIGINT REFERENCES contacts(id),
    owner_id        BIGINT REFERENCES users(id),
    stage           VARCHAR(30) NOT NULL DEFAULT 'Qualification',
    deal_value      BIGINT NOT NULL DEFAULT 0,                  -- named deal_value, not value: VALUE is a reserved word in H2
    probability     INT NOT NULL DEFAULT 50,
    close_date      DATE,
    notes           TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_opportunities_org ON opportunities(organization_id);

CREATE TABLE tasks (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    title           VARCHAR(300) NOT NULL,
    priority        VARCHAR(10) NOT NULL DEFAULT 'Medium',
    status          VARCHAR(20) NOT NULL DEFAULT 'Todo',
    assignee_id     BIGINT REFERENCES users(id),
    due_date        DATE,
    completed_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_tasks_org ON tasks(organization_id);

CREATE TABLE activities (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    type            VARCHAR(20) NOT NULL,
    subject         VARCHAR(300),
    body            TEXT,
    user_id         BIGINT REFERENCES users(id),
    related_type    VARCHAR(20),
    related_id      BIGINT,
    outcome         VARCHAR(120),
    scheduled_at    TIMESTAMP,
    completed_at    TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_activities_org ON activities(organization_id);

-- Immutable, write-once audit log (updated_at intentionally absent).
CREATE TABLE audit_events (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    user_id         BIGINT,
    user_name       VARCHAR(150),
    action          VARCHAR(50) NOT NULL,
    resource        VARCHAR(100) NOT NULL,
    resource_id     VARCHAR(50),
    resource_name   VARCHAR(300),
    before_json      TEXT,
    after_json       TEXT,
    ip_address      VARCHAR(50),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_org ON audit_events(organization_id);
CREATE INDEX idx_audit_resource ON audit_events(resource, resource_id);

-- Seed org + admin so the app is usable immediately (mirrors the Node app's auto-seed).
INSERT INTO organizations (name, slug, plan) VALUES ('Better Analytics', 'better-analytics', 'enterprise');
-- password hash below is a real bcrypt hash of 'Admin@1234' — change on first login in production
INSERT INTO users (organization_id, name, email, password, role, initials)
VALUES (1, 'Admin', 'admin@betteranalytics.in', '$2b$10$t.gq/UfxLzcHm7Ajl5hWFOezhj8v5Ytb70U/OM0a7lUqcxjWEPpFu', 'ADMIN', 'AD');
