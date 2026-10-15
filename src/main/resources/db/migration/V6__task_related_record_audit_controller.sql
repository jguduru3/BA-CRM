-- V6__task_related_record_audit_controller.sql
-- Three additive changes to complete Phase 1:
--   1. Tasks can now be linked to a CRM record (Lead, Opportunity, Contact, Company).
--      The frontend "Add Task" panel on a Lead/Opportunity record uses these columns
--      to filter tasks by context. Spec implies tasks are created in context of a record.
--   2. Soft-delete columns for companies and contacts were already in V4; no change needed.

-- ── 1. Tasks: link to owning CRM record ──────────────────────────────────────
ALTER TABLE tasks ADD COLUMN related_type VARCHAR(20);   -- Lead | Opportunity | Contact | Company
ALTER TABLE tasks ADD COLUMN related_id   BIGINT;        -- FK-like but polymorphic, no DB constraint

CREATE INDEX idx_tasks_related ON tasks(related_type, related_id);
