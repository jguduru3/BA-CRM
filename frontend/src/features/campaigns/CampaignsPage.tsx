import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Megaphone, Plus } from "lucide-react";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { QueryError } from "@/components/ui/QueryError";
import { Tabs } from "@/components/ui/Tabs";
import { CampaignDetailDrawer } from "./CampaignDetailDrawer";
import { CampaignForm, type CampaignDraft, validateTemplateParams } from "./CampaignForm";
import { type CampaignRow, type CampaignStatus, CHANNEL_LABEL, normalizeStatus, STATUS_LABEL, STATUS_TONE } from "./campaignTypes";

type Filter = "all" | "review" | "active" | "done";
const FILTERS: Record<Filter, CampaignStatus[] | null> = {
  all: null,
  review: ["draft", "submitted", "rejected", "approved"],
  active: ["scheduled", "sending", "paused"],
  done: ["completed", "cancelled"],
};
const EMPTY_DRAFT: CampaignDraft = { type: "whatsapp", templateLanguage: "en_US" };

function RowProgress({ r }: { r: CampaignRow }) {
  const total = r.totalContacts || 0;
  const done = Math.min(total, (r.sentCount ?? 0) + (r.failedCount ?? 0));
  const pct = total === 0 ? 0 : Math.round((done / total) * 100);
  const status = normalizeStatus(r.status);
  if (total === 0) return <span className="text-xs" style={{ color: "var(--ink-muted)" }}>No recipients</span>;
  return (
    <div className="min-w-[8rem]">
      <div className="flex justify-between text-[11px] mb-1" style={{ color: "var(--ink-muted)" }}>
        <span className="tabular-nums">
          {done}/{total}
        </span>
        <span className="tabular-nums">{pct}%</span>
      </div>
      <div className="h-1.5 rounded-full overflow-hidden" style={{ backgroundColor: "rgb(var(--panel-border) / 0.12)" }}>
        <div
          className="h-full rounded-full transition-[width] duration-700"
          style={{
            width: `${pct}%`,
            backgroundImage: status === "sending" ? "var(--brand-gradient)" : undefined,
            backgroundColor: status === "sending" ? undefined : status === "completed" ? "var(--good)" : "var(--ink-muted)",
          }}
        />
      </div>
    </div>
  );
}

export default function CampaignsPage() {
  const user = useAuthStore((s) => s.user);
  const canManage = user?.role === "SUPER_ADMIN" || user?.role === "VP_SALES";
  const [filter, setFilter] = useState<Filter>("all");
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<CampaignDraft>(EMPTY_DRAFT);
  const qc = useQueryClient();

  const list = useQuery({
    queryKey: ["campaigns", "list"],
    queryFn: async () => (await api.get<CampaignRow[]>("/campaigns", { params: { limit: 200 } })).data,
    // Keep counters moving while anything is sending.
    refetchInterval: (q) => (q.state.data?.some((c) => normalizeStatus(c.status) === "sending") ? 10_000 : false),
  });

  const rows = useMemo(() => {
    const all = (list.data ?? []).map((c) => ({ ...c, status: normalizeStatus(c.status) }));
    const allowed = FILTERS[filter];
    return allowed ? all.filter((c) => allowed.includes(c.status)) : all;
  }, [list.data, filter]);
  const counts = useMemo(() => {
    const all = (list.data ?? []).map((c) => normalizeStatus(c.status));
    const n = (f: Filter) => (FILTERS[f] ? all.filter((s) => FILTERS[f]!.includes(s)).length : all.length);
    return { all: n("all"), review: n("review"), active: n("active"), done: n("done") };
  }, [list.data]);

  const create = useMutation({
    mutationFn: async () => (await api.post<CampaignRow>("/campaigns", draft)).data,
    onSuccess: (c) => {
      toast.success("Draft created — add recipients next");
      qc.invalidateQueries({ queryKey: ["campaigns"] });
      setCreating(false);
      setDraft(EMPTY_DRAFT);
      setSelectedId(c.id);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const columns: Column<CampaignRow>[] = [
    {
      key: "name",
      header: "Campaign",
      sortValue: (r) => r.name,
      render: (r) => (
        <div className="min-w-0">
          <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
            {r.name}
          </p>
          <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
            {CHANNEL_LABEL[r.type] ?? r.type}
            {r.type === "whatsapp" && r.templateName ? ` · ${r.templateName}` : ""}
            {r.type === "email" && r.emailSubject ? ` · ${r.emailSubject}` : ""}
          </p>
        </div>
      ),
    },
    {
      key: "status",
      header: "Status",
      sortValue: (r) => r.status,
      render: (r) => {
        const s = normalizeStatus(r.status);
        return (
          <span className={`${STATUS_TONE[s]} inline-flex items-center gap-1`}>
            {s === "sending" && <span className="h-1.5 w-1.5 rounded-full animate-pulse" style={{ backgroundColor: "currentColor" }} />}
            {STATUS_LABEL[s]}
          </span>
        );
      },
    },
    { key: "progress", header: "Progress", render: (r) => <RowProgress r={r} /> },
    {
      key: "delivered",
      header: "Delivered",
      align: "right",
      sortValue: (r) => r.deliveredCount,
      render: (r) => (
        <span className="tabular-nums text-sm" style={{ color: "var(--good)" }}>
          {r.deliveredCount ?? 0}
        </span>
      ),
    },
    {
      key: "failed",
      header: "Failed",
      align: "right",
      sortValue: (r) => r.failedCount,
      render: (r) => (
        <span className="tabular-nums text-sm" style={{ color: r.failedCount ? "var(--critical)" : "var(--ink-muted)" }}>
          {r.failedCount ?? 0}
        </span>
      ),
    },
    {
      key: "createdAt",
      header: "Created",
      sortValue: (r) => r.createdAt,
      render: (r) => <span className="text-sm whitespace-nowrap">{formatDateTime(r.createdAt)}</span>,
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Campaigns"
        description="Draft → approval → scheduled → sending → completed, with live delivery tracking."
        icon={<Megaphone size={20} />}
        actions={
          canManage ? (
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <Plus size={15} /> New campaign
            </button>
          ) : undefined
        }
      />

      <div className="overflow-x-auto thin-scroll -mx-1 px-1">
        <Tabs
          value={filter}
          onChange={setFilter}
          tabs={[
            { value: "all", label: "All", count: counts.all },
            { value: "review", label: "In review", count: counts.review },
            { value: "active", label: "Active", count: counts.active },
            { value: "done", label: "Finished", count: counts.done },
          ]}
        />
      </div>

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load campaigns" />
        </div>
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          loading={list.isLoading}
          onRowClick={(r) => setSelectedId(r.id)}
          emptyTitle={filter === "all" ? "No campaigns yet" : "Nothing in this view"}
          emptyDescription={canManage ? "Create a WhatsApp campaign, pick an approved template and build its audience." : undefined}
          emptyAction={
            canManage && filter === "all" ? (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> New campaign
              </button>
            ) : undefined
          }
        />
      )}

      <CampaignDetailDrawer campaignId={selectedId} onClose={() => setSelectedId(null)} />

      <Drawer open={creating} onClose={() => setCreating(false)} title="New campaign" subtitle="Saved as a draft — nothing is sent yet" width="34rem">
        <div className="space-y-5">
          <CampaignForm value={draft} onChange={setDraft} isNew />
          <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
            <button className="btn-ghost" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button
              className="btn-primary"
              disabled={!draft.name?.trim() || !!validateTemplateParams(draft.templateParams) || create.isPending}
              onClick={() => create.mutate()}
            >
              {create.isPending ? "Creating…" : "Create draft"}
            </button>
          </div>
        </div>
      </Drawer>
    </div>
  );
}
