-- V42: indexes for the filters/sorts that the workspace-tool list endpoints now
-- push into the database (todos, projects, calendar, meetings, notes, documents,
-- reminders, record pages, custom-database records). Plain composite indexes —
-- no partial indexes, so they work on H2 and PostgreSQL alike.

CREATE INDEX idx_todo_lists_org_owner        ON todo_lists(organization_id, owner_id);
CREATE INDEX idx_todo_items_parent           ON todo_items(parent_id);

CREATE INDEX idx_projects_org_status         ON projects(organization_id, status);
CREATE INDEX idx_projects_org_owner          ON projects(organization_id, owner_id);
CREATE INDEX idx_projects_org_created        ON projects(organization_id, created_at);
CREATE INDEX idx_project_milestones_project  ON project_milestones(project_id, position);
CREATE INDEX idx_project_members_org_project ON project_members(organization_id, project_id);
CREATE INDEX idx_ws_tasks_project            ON tasks(organization_id, project_id);

CREATE INDEX idx_calendar_events_owner_start ON calendar_events(organization_id, owner_id, start_at);
CREATE INDEX idx_calendar_events_related     ON calendar_events(organization_id, related_type, related_id);

CREATE INDEX idx_meetings_org_start          ON meetings(organization_id, start_at);
CREATE INDEX idx_meetings_org_organizer      ON meetings(organization_id, organizer_id, start_at);

CREATE INDEX idx_notes_org_created           ON notes(organization_id, created_at);
CREATE INDEX idx_notes_org_author            ON notes(organization_id, created_by);

CREATE INDEX idx_documents_org_updated       ON documents(organization_id, updated_at);

CREATE INDEX idx_reminders_pending           ON reminders(is_fired, remind_at);
CREATE INDEX idx_reminders_user              ON reminders(organization_id, user_id, remind_at);

CREATE INDEX idx_page_blocks_page_position   ON page_blocks(page_id, position);

CREATE INDEX idx_db_records_db_org_created   ON database_records(database_id, organization_id, created_at);
CREATE INDEX idx_property_values_record_prop ON property_values(record_id, property_id);
CREATE INDEX idx_db_properties_db_position   ON database_properties(database_id, position);
