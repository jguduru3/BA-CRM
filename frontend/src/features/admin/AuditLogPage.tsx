import { useQuery } from "@tanstack/react-query";
import { History } from "lucide-react";
import { useState } from "react";
import { getList } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { AuditEvent } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Column, DataTable } from "@/components/ui/DataTable";
import { SearchInput } from "@/components/ui/SearchInput";

const ACTION_TONE: Record<string, string> = {
  create: "badge-good",
  update: "badge-brand",
  delete: "badge-critical",
  permission_denied: "badge-critical",
  login: "badge-neutral",
  approve: "badge-good",
  reject: "badge-critical",
};

export default function AuditLogPage() {
  const [resource, setResource] = useState("");

  const list = useQuery({
    queryKey: ["audit-events", resource],
    queryFn: () => getList<AuditEvent>("/audit-events", resource ? { resource, limit: 500 } : { limit: 500 }),
  });

  const columns: Column<AuditEvent>[] = [
    {
      key: "action",
      header: "Action",
      sortValue: (r) => r.action,
      render: (r) => <span className={ACTION_TONE[r.action] ?? "badge-neutral"}>{r.action.replace("_", " ")}</span>,
    },
    { key: "resource", header: "Resource", sortValue: (r) => r.resource, render: (r) => <span className="text-sm">{r.resource}</span> },
    { key: "resourceName", header: "Record", render: (r) => <span className="text-sm truncate">{r.resourceName ?? r.resourceId ?? "—"}</span> },
    { key: "userName", header: "Actor", sortValue: (r) => r.userName ?? "", render: (r) => <span className="text-sm">{r.userName ?? "System"}</span> },
    { key: "createdAt", header: "When", sortValue: (r) => r.createdAt, render: (r) => <span className="text-sm">{formatDateTime(r.createdAt)}</span> },
  ];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Audit Log"
        description={`${list.data?.count ?? 0} events · immutable write-once log`}
        icon={<History size={20} />}
      />
      <SearchInput value={resource} onChange={setResource} placeholder="Filter by resource (e.g. leads, opportunities)…" className="max-w-xs" />
      <DataTable columns={columns} rows={list.data?.data ?? []} loading={list.isLoading} emptyTitle="No audit events yet" />
    </div>
  );
}
