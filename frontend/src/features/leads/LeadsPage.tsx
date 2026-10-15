import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Target } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api, getList } from "@/lib/api";
import { useCompaniesLookup, useUsersLookup } from "@/lib/hooks";
import { formatCompactCurrency, formatDate, isOverdue } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Lead, PipelineResponse } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { PriorityBadge, StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Kanban, KanbanColumnDef } from "@/components/ui/Kanban";
import { SearchInput } from "@/components/ui/SearchInput";
import { Tabs } from "@/components/ui/Tabs";
import { LeadDrawer } from "./LeadDrawer";
import { LeadKanbanCard } from "./LeadKanbanCard";
import { LEAD_STAGES } from "./LeadForm";

const STAGE_ACCENTS: Record<string, string> = {
  New: "#2a78d6",
  Contacted: "#4a3aa7",
  Meeting: "#eda100",
  Proposal: "#eb6834",
  Negotiation: "#e87ba4",
  Won: "#0ca30c",
  Lost: "#d03b3b",
};

type GroupBy = "stage" | "source" | "leadType" | "category";

export default function LeadsPage() {
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState<"table" | "pipeline">("table");
  const [groupBy, setGroupBy] = useState<GroupBy>("stage");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<Lead | null>(null);
  const [creating, setCreating] = useState(false);
  const qc = useQueryClient();

  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const companyName = (id: number | null) => companies.data?.data.find((c) => c.id === id)?.name;

  const list = useQuery({
    queryKey: ["leads", { search, limit: 500 }],
    queryFn: () => getList<Lead>("/leads", { search, limit: 500 }),
  });

  const pipeline = useQuery({
    queryKey: ["leads", "pipeline", groupBy],
    queryFn: async () => (await api.get<PipelineResponse>("/leads/pipeline", { params: { groupBy } })).data,
    enabled: view === "pipeline" && groupBy !== "stage",
  });

  // Highlight-from-URL support (?id=123) so other pages can deep link in.
  useEffect(() => {
    const id = params.get("id");
    if (id && list.data) {
      const l = list.data.data.find((x) => x.id === Number(id));
      if (l) {
        setSelected(l);
        params.delete("id");
        setParams(params, { replace: true });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list.data]);

  const stageColumns: KanbanColumnDef[] = LEAD_STAGES.map((s) => ({ key: s, label: s, accent: STAGE_ACCENTS[s] }));
  const stageItems: Record<string, Lead[]> = useMemo(() => {
    const map: Record<string, Lead[]> = {};
    for (const s of LEAD_STAGES) map[s] = [];
    for (const lead of list.data?.data ?? []) {
      (map[lead.stage] ??= []).push(lead);
    }
    return map;
  }, [list.data]);

  async function moveStage(id: number, _from: string, to: string) {
    try {
      await api.put(`/leads/${id}`, { stage: to });
      qc.invalidateQueries({ queryKey: ["leads"] });
      toast.success(`Moved to ${to}`);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not move lead");
    }
  }

  const dynamicColumns: KanbanColumnDef[] = pipeline.data
    ? Object.keys(pipeline.data.columns).map((k) => ({ key: k, label: k }))
    : [];
  const dynamicItems: Record<string, Lead[]> = pipeline.data?.columns ?? {};

  const columns: Column<Lead>[] = [
    {
      key: "name",
      header: "Lead",
      sortValue: (r) => r.name,
      render: (r) => (
        <div className="flex items-center gap-2.5 min-w-0">
          <Avatar name={r.name} size={28} />
          <div className="min-w-0">
            <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
              {r.name}
            </p>
            <p className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
              {companyName(r.companyId) ?? r.businessName ?? r.email ?? "—"}
            </p>
          </div>
        </div>
      ),
    },
    { key: "stage", header: "Stage", sortValue: (r) => r.stage, render: (r) => <StageBadge value={r.stage} /> },
    { key: "source", header: "Source", sortValue: (r) => r.source, render: (r) => <span className="text-sm">{r.source}</span> },
    { key: "priority", header: "Priority", sortValue: (r) => r.priority, render: (r) => <PriorityBadge value={r.priority} /> },
    {
      key: "value",
      header: "Value",
      align: "right",
      sortValue: (r) => r.value,
      render: (r) => <span className="text-sm font-semibold tabular-nums">{formatCompactCurrency(r.value)}</span>,
    },
    {
      key: "followUpAt",
      header: "Follow-up",
      sortValue: (r) => r.followUpAt ?? "",
      render: (r) => (
        <span
          className="text-sm"
          style={{ color: isOverdue(r.followUpAt) ? "var(--critical)" : "var(--ink-secondary)" }}
        >
          {formatDate(r.followUpAt)}
        </span>
      ),
    },
    {
      key: "owner",
      header: "Owner",
      render: (r) =>
        r.ownerId ? (
          <div className="flex items-center gap-2">
            <Avatar name={userName(r.ownerId)} size={22} />
            <span className="text-sm truncate">{userName(r.ownerId)}</span>
          </div>
        ) : (
          <span className="badge-neutral">Unassigned</span>
        ),
    },
  ];

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <PageHeader
        title="Leads"
        description={`${list.data?.count ?? 0} leads in your scope`}
        icon={<Target size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New lead
          </button>
        }
      />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs
          value={view}
          onChange={setView}
          tabs={[
            { value: "table", label: "Table" },
            { value: "pipeline", label: "Pipeline" },
          ]}
        />
        <div className="flex items-center gap-2 flex-1 sm:flex-initial">
          {view === "pipeline" && (
            <select className="select w-auto" value={groupBy} onChange={(e) => setGroupBy(e.target.value as GroupBy)}>
              <option value="stage">Group by stage</option>
              <option value="source">Group by source</option>
              <option value="leadType">Group by lead type</option>
              <option value="category">Group by category</option>
            </select>
          )}
          <SearchInput value={search} onChange={setSearch} placeholder="Search leads…" className="w-full sm:w-64" />
        </div>
      </div>

      {view === "table" ? (
        <DataTable
          columns={columns}
          rows={list.data?.data ?? []}
          loading={list.isLoading}
          onRowClick={setSelected}
          emptyTitle="No leads yet"
          emptyDescription="Create your first lead to start building pipeline."
          emptyAction={
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <Plus size={15} /> New lead
            </button>
          }
        />
      ) : (
        <div className="flex-1 min-h-[520px]">
          <Kanban
            columns={groupBy === "stage" ? stageColumns : dynamicColumns}
            itemsByColumn={groupBy === "stage" ? stageItems : dynamicItems}
            onMove={moveStage}
            draggable={groupBy === "stage"}
            loading={groupBy !== "stage" && pipeline.isLoading}
            renderCard={(lead) => (
              <div onClick={() => setSelected(lead)}>
                <LeadKanbanCard lead={lead} companyName={companyName(lead.companyId)} ownerName={userName(lead.ownerId)} />
              </div>
            )}
          />
        </div>
      )}

      <LeadDrawer lead={selected} open={!!selected} onClose={() => setSelected(null)} />
      <LeadDrawer lead={null} open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}
