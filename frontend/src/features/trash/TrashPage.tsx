import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { RotateCcw, Trash2 } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { Tabs } from "@/components/ui/Tabs";
import { SkeletonRows } from "@/components/ui/Skeleton";
import { QueryError } from "@/components/ui/QueryError";

const RESOURCES = [
  { value: "leads", label: "Leads", nameKey: "name" },
  { value: "companies", label: "Companies", nameKey: "name" },
  { value: "contacts", label: "Contacts", nameKey: "name" },
  { value: "opportunities", label: "Opportunities", nameKey: "name" },
  { value: "tasks", label: "Tasks", nameKey: "title" },
] as const;

type ResourceKey = (typeof RESOURCES)[number]["value"];

export default function TrashPage() {
  const [resource, setResource] = useState<ResourceKey>("leads");
  const qc = useQueryClient();
  const meta = RESOURCES.find((r) => r.value === resource)!;

  const list = useQuery({
    queryKey: ["trash", resource],
    queryFn: async () => (await api.get<Array<{ id: number; name?: string; title?: string; deletedAt?: string; deleteReason?: string | null }>>(`/${resource}/trash`)).data,
  });

  const restore = useMutation({
    mutationFn: async (id: number) => api.post(`/${resource}/${id}/restore`),
    onSuccess: () => {
      toast.success("Record restored");
      qc.invalidateQueries({ queryKey: ["trash", resource] });
      qc.invalidateQueries({ queryKey: [resource] });
      // restoring a company also brings back the records its delete cascaded to
      if (resource === "companies") ["contacts", "opportunities", "leads"].forEach((k) => qc.invalidateQueries({ queryKey: [k] }));
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return (
    <div className="flex flex-col gap-4">
      <PageHeader title="Trash" description="Soft-deleted records, recoverable at any time." icon={<Trash2 size={20} />} />

      <Tabs value={resource} onChange={setResource} tabs={RESOURCES.map((r) => ({ value: r.value, label: r.label }))} />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title={`Couldn't load deleted ${meta.label.toLowerCase()}`} />
        </div>
      ) : list.isLoading ? (
        <div className="glass-card">
          <SkeletonRows rows={5} cols={3} />
        </div>
      ) : (list.data ?? []).length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={Trash2} title={`No deleted ${meta.label.toLowerCase()}`} description="Deleted records show up here and can be restored." />
        </div>
      ) : (
        <div className="glass-card divide-y">
          {list.data!.map((r) => (
            <div key={r.id} className="flex items-center justify-between gap-3 p-4 hairline border-b last:border-b-0">
              <div className="min-w-0">
                <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                  {r[meta.nameKey] ?? `#${r.id}`}
                </p>
                <p className="text-xs mt-0.5" style={{ color: "var(--ink-muted)" }}>
                  Deleted {formatDateTime(r.deletedAt)}
                  {r.deleteReason ? ` — ${r.deleteReason}` : ""}
                </p>
              </div>
              <button className="btn-secondary shrink-0" onClick={() => restore.mutate(r.id)}>
                <RotateCcw size={14} /> Restore
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
