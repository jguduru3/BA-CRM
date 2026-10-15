-- V3__role_hierarchy.sql
-- Replaces the flat ADMIN/SALES_MANAGER/SALES_REP/SUPPORT role set with the
-- 6-role sales hierarchy from the "CRM-Role_based_Features" spec.
--
-- CRITICAL: role is stored as a plain VARCHAR (@Enumerated(STRING), no DB
-- CHECK constraint), so nothing at the schema level stops old values from
-- lingering — but Role.valueOf() in Java WILL throw on any row still holding
-- the old string once the enum constants are removed. The UPDATEs below are
-- not optional cleanup; without them every existing user with the old role
-- values fails to deserialize the moment this migration's Java-side changes
-- ship.
--
-- Mapping (old -> new):
--   ADMIN         -> SUPER_ADMIN   (closest existing equivalent)
--   SALES_MANAGER -> MANAGER
--   SALES_REP     -> EXECUTIVE     (a default guess — reclassify individual
--                                   users to INSIDE_SALES/FIELD_SALES via the
--                                   Users page as needed; there is no way to
--                                   infer which of the three they actually
--                                   were from the old data alone)
--   SUPPORT       -> SUPPORT       (unchanged; kept as a non-sales,
--                                   org-wide read-only role outside the tree)
UPDATE users SET role = 'SUPER_ADMIN' WHERE role = 'ADMIN';
UPDATE users SET role = 'MANAGER'     WHERE role = 'SALES_MANAGER';
UPDATE users SET role = 'EXECUTIVE'   WHERE role = 'SALES_REP';

-- NEW — the reporting-tree primitive PermissionService will walk later.
-- Nullable: existing users start with no reporting line set; VP_SALES stays
-- null permanently (top of the sales tree); SUPER_ADMIN/SUPPORT never use it.
ALTER TABLE users ADD COLUMN reports_to_id BIGINT REFERENCES users(id);
CREATE INDEX idx_users_reports_to ON users(reports_to_id);

-- NEW — free-text territory tag for FIELD_SALES routing (see Role.java).
ALTER TABLE users ADD COLUMN territory VARCHAR(150);
