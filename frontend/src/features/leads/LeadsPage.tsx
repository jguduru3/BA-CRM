import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Hand, Plus, Shuffle, Target } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api, getList } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { useCompaniesLookup, useUsersLookup } from "@/lib/hooks";
import { formatCompactCurrency, formatDate, formatRelative, isOverdue } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { PriorityBadge, StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Kanban, KanbanColumnDef } from "@/components/ui/Kanban";
import { QueryError } from "@/components/ui/QueryError";
import { SearchInput } from "@/components/ui/SearchInput";
import { Tabs } from "@/components/ui/Tabs";
import { ExportButton, FilterSelect, ListToolbar, saveErrorMessage } from "@/features/crm/ListParts";
import type { LeadRecord } from "@/features/crm/types";
import { useServerList } from "@/features/crm/useServerList";
import { LeadDrawer } from "./LeadDrawer";
import { LeadKanbanCard } from "./LeadKanbanCard";
import { LeadRoutingSettings } from "./LeadRoutingSettings";
import { LEAD_SOURCES, LEAD_STAGES, PRIORITIES } from "./LeadForm";

const STAGE_ACCENTS: Record<string, string> = {
  New: "#2a78d6",
  Contacted: "#4a3aa7",
  Meeting: "#eda100",
  Proposal: "#eb6834",
  Negotiation: "#e87ba4",
  Won: "#0ca30c",
  Lost: "#d03b3b",
};

type View = "table" | "pipeline" | "pool";
type GroupBy = "stage" | "source" | "leadType" | "category";

interface PipelineData {
  groupBy: string;
  columns: Record<string, LeadRecord[]>;
}

export default function LeadsPage() {
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState<View>("table");
  const [groupBy, setGroupBy] = useState<GroupBy>("stage");
  const [stage, setStage] = useState("");
  const [source, setSource] = useState("");
  const [priority, setPriority] = useState("");
  const [overdue, setOverdue] = useState(false);
  const [selected, setSelected] = useState<LeadRecord | null>(null);
  const [creating, setCreating] = useState(false);
  const [routingOpen, setRoutingOpen] = useState(false);
  const qc = useQueryClient();
  const role = useAuthStore((s) => s.user?.role);
  const readOnly = isReadOnly(role);
  const canClaim = role === "EXECUTIVE" || role === "INSIDE_SALES" || role === "FIELD_SALES";

  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const companyName = (id: number | null) => companies.data?.data.find((c) => c.id === id)?.name;

  const table = useServerList<LeadRecord>("leads", {
    filters: { stage, source, priority, overdue, pool: view === "pool" },
    defaultSort: { key: "updatedAt", dir: "desc" },
    enabled: view !== "pipeline",
  });

  const poolCount = useQuery({
    queryKey: ["leads", "pool-count"],
    queryFn: () => getList<LeadRecord>("/leads", { pool: true, size: 1 }),
  });

  const pipeline = useQuery({
    queryKey: ["leads", "pipeline", groupBy],
    queryFn: async () => (await api.get<PipelineData>("/leads/pipeline", { params: { groupBy } })).data,
    enabled: view === "pipeline",
  });

  // Deep link from search / other pages: ?open=123 (or legacy ?id=123)
  useEffect(() => {
    const id = params.get("open") ?? params.get("id");
    if (!id) return;
    api
      .get<LeadRecord>(`/leads/${id}`)
      .then((r) => {
        setCreating(false);
        setSelected(r.data);
      })
      .catch((e) => toast.error(e instanceof Error ? e.message : "Lead not found"));
    params.delete("open");
    params.delete("id");
    setParams(params, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.get("open") ?? params.get("id")]);

  const claim = useMutation({
    mutationFn: async (id: number) => (await api.post<LeadRecord>(`/leads/${id}/claim`)).data,
    onSuccess: (lead) => {
      toast.success(`"${lead.name}" is yours now`);
      qc.invalidateQueries({ queryKey: ["leads"] });
    },
    onError: (e: Error) => {
      toast.error(e.message);
      qc.invalidateQueries({ queryKey: ["leads"] });
    },
  });

  async function moveStage(id: number, _from: string, to: string) {
    try {
      await api.patch(`/leads/${id}`, { stage: to });
      qc.invalidateQueries({ queryKey: ["leads"] });
      toast.success(`Moved to ${to}`);
    } catch (e) {
      toast.error(saveErrorMessage(e, "lead"));
    }
  }

  const boardColumns: KanbanColumnDef[] = useMemo(() => {
    if (groupBy === "stage") return LEAD_STAGES.map((s) => ({ key: s, label: s, accent: STAGE_ACCENTS[s] }));
    return Object.keys(pipeline.data?.columns ?? {}).map((k) => ({ key: k, label: k }));
  }, [groupBy, pipeline.data]);

  const columns: Column<LeadRecord>[] = [
    {
      key: "name",
      header: "Lead",
      sortKey: "name",
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
    { key: "stage", header: "Stage", sortKey: "stage", render: (r) => <StageBadge value={r.stage} /> },
    { key: "source", header: "Source", sortKey: "source", hideOnMobile: true, render: (r) => <span className="text-sm">{r.source}</span> },
    { key: "priority", header: "Priority", sortKey: "priority", hideOnMobile: true, render: (r) => <PriorityBadge value={r.priority} /> },
    {
      key: "value",
      header: "Value",
      align: "right",
      sortKey: "value",
      render: (r) => <span className="text-sm font-semibold tabular-nums">{formatCompactCurrency(r.value)}</span>,
    },
    {
      key: "followUpAt",
      header: "Follow-up",
      sortKey: "followUpAt",
      hideOnMobile: true,
      render: (r) => (
        <span className="text-sm" style={{ color: isOverdue(r.followUpAt) ? "var(--critical)" : "var(--ink-secondary)" }}>
          {formatDate(r.followUpAt)}
          {r.followUpRescheduleCount ? (
            <span className="ml-1 text-[11px]" style={{ color: "var(--ink-muted)" }} title="Times rescheduled">
              ↻{r.followUpRescheduleCount}
            </span>
          ) : null}
        </span>
      ),
    },
    view === "pool"
      ? {
          key: "claim",
          header: "",
          align: "right",
          render: (r) =>
            canClaim ? (
              <button
                className="btn-secondary h-8 px-3 text-xs"
                disabled={claim.isPending}
                onClick={(e) => {
                  e.stopPropagation();
                  claim.mutate(r.id);
                }}
              >
                <Hand size={13} /> Claim
              </button>
            ) : (
              <span className="text-xs" style={{ color: "var(--ink-muted)" }}>
                Added {formatRelative(r.createdAt)}
              </span>
            ),
        }
      : {
          key: "owner",
          header: "Owner",
          hideOnMobile: true,
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

  const pooled = poolCount.data?.count ?? 0;
  const activeQuery = view === "pipeline" ? pipeline : table.query;

  return (
    <div className="flex flex-col gap-4 flex-1 min-h-0">
      <PageHeader
        title="Leads"
        description={
          table.query.data
            ? `${table.total.toLocaleString()} ${view === "pool" ? "unassigned" : "in your scope"}${pooled ? ` · ${pooled} waiting in the pool` : ""}`
            : "Your prospects, from first touch to hand-off"
        }
        icon={<Target size={20} />}
        actions={
          <>
            <ExportButton type="leads" />
            {isManagerUp(role) && !readOnly && (
              <button className="btn-secondary" onClick={() => setRoutingOpen(true)} title="Round-robin lead routing">
                <Shuffle size={15} /> <span className="hidden sm:inline">Routing</span>
              </button>
            )}
            {!readOnly && (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> <span className="hidden sm:inline">New lead</span>
              </button>
            )}
          </>
        }
      />

      <ListToolbar
        left={
          <>
            <Tabs
              value={view}
              onChange={setView}
              tabs={[
                { value: "table", label: "Table" },
                { value: "pipeline", label: "Pipeline" },
                { value: "pool", label: "Pool", count: pooled || undefined },
              ]}
            />
            {view === "pipeline" ? (
              <select className="select w-auto" aria-label="Group by" value={groupBy} onChange={(e) => setGroupBy(e.target.value as GroupBy)}>
                <option value="stage">Group by stage</option>
                <option value="source">Group by source</option>
                <option value="leadType">Group by lead type</option>
                <option value="category">Group by category</option>
              </select>
            ) : (
              <>
                <FilterSelect ariaLabel="Stage" allLabel="All stages" value={stage} onChange={setStage} options={LEAD_STAGES} />
                <FilterSelect ariaLabel="Source" allLabel="All sources" value={source} onChange={setSource} options={LEAD_SOURCES} />
                <FilterSelect ariaLabel="Priority" allLabel="Any priority" value={priority} onChange={setPriority} options={PRIORITIES} />
                <label className="inline-flex items-center gap-1.5 text-sm cursor-pointer" style={{ color: "var(--ink-secondary)" }}>
                  <input type="checkbox" checked={overdue} onChange={(e) => setOverdue(e.target.checked)} />
                  Overdue follow-up
                </label>
              </>
            )}
          </>
        }
        right={
          view !== "pipeline" && (
            <SearchInput value={table.search} onChange={table.setSearch} placeholder="Search name, business, email…" className="w-full sm:w-72" />
          )
        }
      />

      {activeQuery.isError ? (
        <div className="glass-card">
          <QueryError error={activeQuery.error} onRetry={() => activeQuery.refetch()} title="Couldn't load leads" />
        </div>
      ) : view === "pipeline" ? (
        <div className="flex-1 min-h-[520px]">
          <Kanban
            columns={boardColumns}
            itemsByColumn={pipeline.data?.columns ?? {}}
            onMove={moveStage}
            draggable={groupBy === "stage" && !readOnly}
            loading={pipeline.isLoading}
            renderCard={(lead) => (
              <div onClick={() => setSelected(lead)}>
                <LeadKanbanCard lead={lead} companyName={companyName(lead.companyId)} ownerName={userName(lead.ownerId)} />
              </div>
            )}
          />
        </div>
      ) : (
        <DataTable
          columns={columns}
          rows={table.rows}
          loading={table.query.isLoading}
          fetching={table.query.isFetching && !table.query.isLoading}
          onRowClick={setSelected}
          pagination={table.pagination}
          sort={table.sort}
          onSortChange={table.setSort}
          emptyTitle={view === "pool" ? "The pool is empty" : table.search || stage || source ? "No leads match" : "No leads yet"}
          emptyDescription={
            view === "pool"
              ? "Unassigned leads land here. Reps claim them; managers assign them from the lead panel."
              : table.search || stage || source
              ? "Try a different search or clear the filters."
              : "Create your first lead to start building pipeline."
          }
          emptyAction={
            view !== "pool" && !readOnly ? (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> New lead
              </button>
            ) : undefined
          }
        />
      )}

      <LeadDrawer lead={selected} open={!!selected} onClose={() => setSelected(null)} />
      <LeadDrawer lead={null} open={creating} onClose={() => setCreating(false)} />
      <LeadRoutingSettings open={routingOpen} onClose={() => setRoutingOpen(false)} />
    </div>
  );
}
