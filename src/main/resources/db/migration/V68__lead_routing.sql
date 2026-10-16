-- V68 — round-robin lead routing, one row per organization.
-- team_id NULL = every active sales rep of the org. last_assigned_user_id is the
-- rotation pointer; it only moves through a compare-and-set on `version`, so
-- concurrent lead creations each get the next rep exactly once.
CREATE TABLE routing_settings (
    id                    BIGSERIAL PRIMARY KEY,
    organization_id       BIGINT    NOT NULL REFERENCES organizations(id),
    enabled               BOOLEAN   NOT NULL DEFAULT FALSE,
    team_id               BIGINT,
    respect_territory     BOOLEAN   NOT NULL DEFAULT FALSE,
    last_assigned_user_id BIGINT,
    version               BIGINT    NOT NULL DEFAULT 0,
    updated_by            BIGINT,
    updated_at            TIMESTAMP,
    CONSTRAINT uq_routing_settings_org UNIQUE (organization_id)
);
