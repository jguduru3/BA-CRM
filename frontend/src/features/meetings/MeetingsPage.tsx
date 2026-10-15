import { Video } from "lucide-react";
import { formatDateTime } from "@/lib/format";
import type { Meeting } from "@/lib/types";
import { StageBadge } from "@/components/ui/Badge";
import { Column } from "@/components/ui/DataTable";
import { SimpleCrudPage, SimpleField } from "@/features/generic/SimpleCrudPage";

const STATUSES = ["scheduled", "completed", "cancelled", "no_show", "rescheduled"];
const RELATED_TYPES = ["Lead", "Opportunity", "Contact", "Company"];

const columns: Column<Meeting>[] = [
  {
    key: "title",
    header: "Meeting",
    sortValue: (r) => r.title,
    render: (r) => (
      <div className="min-w-0">
        <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
          {r.title}
        </p>
        {r.relatedType && (
          <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
            {r.relatedType} #{r.relatedId}
          </p>
        )}
      </div>
    ),
  },
  { key: "status", header: "Status", sortValue: (r) => r.status, render: (r) => <StageBadge value={r.status} /> },
  { key: "startAt", header: "When", sortValue: (r) => r.startAt, render: (r) => <span className="text-sm">{formatDateTime(r.startAt)}</span> },
  { key: "location", header: "Location / link", render: (r) => <span className="text-sm truncate">{r.location || r.meetingUrl || "—"}</span> },
];

const fields: SimpleField[] = [
  { key: "title", label: "Title", type: "text", required: true },
  { key: "status", label: "Status", type: "select", options: STATUSES },
  { key: "startAt", label: "Starts at", type: "datetime", required: true },
  { key: "endAt", label: "Ends at", type: "datetime" },
  { key: "location", label: "Location", type: "text" },
  { key: "meetingUrl", label: "Meeting link", type: "text" },
  { key: "relatedType", label: "Related record type", type: "select", options: RELATED_TYPES },
  { key: "relatedId", label: "Related record ID", type: "number" },
  { key: "description", label: "Agenda", type: "textarea", span2: true },
  { key: "notes", label: "Notes", type: "textarea", span2: true },
  { key: "outcome", label: "Outcome", type: "textarea", span2: true },
];

export default function MeetingsPage() {
  return (
    <SimpleCrudPage<Meeting>
      resource="meetings"
      label="Meetings"
      icon={Video}
      columns={columns}
      fields={fields}
      defaults={{ status: "scheduled" }}
      titleOf={(r) => r.title}
      searchable={false}
    />
  );
}
