import { Pin, StickyNote } from "lucide-react";
import { formatRelative } from "@/lib/format";
import type { Note } from "@/lib/types";
import { Column } from "@/components/ui/DataTable";
import { SimpleCrudPage, SimpleField } from "@/features/generic/SimpleCrudPage";

const RELATED_TYPES = ["Lead", "Opportunity", "Contact", "Company", "Meeting"];

const columns: Column<Note>[] = [
  {
    key: "title",
    header: "Note",
    sortValue: (r) => r.title ?? "",
    render: (r) => (
      <div className="flex items-center gap-2 min-w-0">
        {r.pinned && <Pin size={13} style={{ color: "var(--brand-500)" }} className="shrink-0" />}
        <div className="min-w-0">
          <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
            {r.title || "Untitled note"}
          </p>
          <p className="text-xs truncate max-w-md" style={{ color: "var(--ink-muted)" }}>
            {r.body}
          </p>
        </div>
      </div>
    ),
  },
  {
    key: "related",
    header: "Linked to",
    render: (r) => (r.relatedType ? <span className="text-sm">{r.relatedType} #{r.relatedId}</span> : <span className="badge-neutral">General</span>),
  },
  { key: "createdAt", header: "Created", sortValue: (r) => r.createdAt, render: (r) => <span className="text-sm">{formatRelative(r.createdAt)}</span> },
];

const fields: SimpleField[] = [
  { key: "title", label: "Title", type: "text" },
  { key: "body", label: "Note", type: "textarea", required: true, span2: true },
  { key: "pinned", label: "Pin to top", type: "checkbox" },
  { key: "relatedType", label: "Related record type", type: "select", options: RELATED_TYPES },
  { key: "relatedId", label: "Related record ID", type: "number" },
];

export default function NotesPage() {
  return (
    <SimpleCrudPage<Note>
      resource="notes"
      label="Notes"
      icon={StickyNote}
      columns={columns}
      fields={fields}
      defaults={{ pinned: false }}
      titleOf={(r) => r.title || "Note"}
      searchable={false}
    />
  );
}
