-- Phase 4: AI Automation
-- org_settings: one row per organization — AI provider config + API key
CREATE TABLE org_settings (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL UNIQUE,
    ai_provider     VARCHAR(50)  DEFAULT 'openai',
    ai_api_key      TEXT,
    ai_model        VARCHAR(100) DEFAULT 'gpt-4o-mini',
    ai_enabled      BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMP WITH TIME ZONE,
    updated_at      TIMESTAMP WITH TIME ZONE
);

-- ai_jobs: one row per AI automation request; tracks prompt + result + token usage
CREATE TABLE ai_jobs (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT      NOT NULL,
    type            VARCHAR(50)  NOT NULL,   -- score_lead | draft_message | summarize | next_action
    entity_type     VARCHAR(50),
    entity_id       BIGINT,
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',  -- pending | running | done | failed
    input_json      TEXT,
    result_json     TEXT,
    error           TEXT,
    model_used      VARCHAR(100),
    tokens_used     INTEGER,
    created_by      BIGINT,
    created_at      TIMESTAMP WITH TIME ZONE,
    completed_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_ai_jobs_org    ON ai_jobs(organization_id);
CREATE INDEX idx_ai_jobs_entity ON ai_jobs(organization_id, entity_type, entity_id);
