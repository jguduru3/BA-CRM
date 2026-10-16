import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Handshake, Plus, SlidersHorizontal, Trash2, Trophy, XCircle } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api, getList } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { useCompaniesLookup, useUsersLookup } from "@/lib/hooks";
import { formatCompactCurrency, formatDate, formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { OppPipelineRow } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Kanban, KanbanColumnDef } from "@/components/ui/Kanban";
import { QueryError } from "@/components/ui/QueryError";
import { useReason } from "@/components/ui/ReasonDialog";
import { SearchInput } from "@/components/ui/SearchInput";
import { Tabs } from "@/components/ui/Tabs";
import { ExportButton, FilterSelect, ListToolbar, saveErrorMessage } from "@/features/crm/ListParts";
import { CLOSED_STAGES, type OpportunityRecord } from "@/features/crm/types";
import { useDebounced, useServerList } from "@/features/crm/useServerList";
import { OppDraft, OpportunityForm, OPP_STAGES } from "./OpportunityForm";
import { OpportunityKanbanCard } from "./OpportunityKanbanCard";
import { PipelineSettingsModal, usePipelineSettings } from "./PipelineSettingsModal";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { RecordPagePanel } from "@/features/recordpages/RecordPagePanel";

const STAGE_ACCENTS: Record<string, string> = {
  Qualification: "#2a78d6",
  Proposal: "#eb6834",
  Negotiation: "#e87ba4",
  "Verbal Commit": "#eda100",
  Won: "#0ca30c",
  Lost: "#d03b3b",
};
/** The board shows up to this many deals (server max page size). */
const BOARD_SIZE = 200;

export default function OpportunitiesPage() {
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState<"pipeline" | "table">("pipeline");
  const [stage, setStage] = useState("");
  const [ownerFilter, setOwnerFilter] = useState("");
  const [boardSearch, setBoardSearch] = useState("");
  const [selected, setSelected] = useState<OpportunityRecord | null>(null);
  const [creating, setCreating] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [draft, setDraft] = useState<OppDraft>({});
  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const settings = usePipelineSettings();
  const qc = useQueryClient();
  const { askReason, node: reasonNode } = useReason();
  const role = useAuthStore((s) => s.user?.role);
  const readOnly = isReadOnly(role);
  const manager = isManagerUp(role);
  const valueHidden = role === "INSIDE_SALES";

  const table = useServerList<OpportunityRecord>("opportunities", {
    filters: { stage, ownerId: ownerFilter },
    defaultSort: { key: "updatedAt", dir: "desc" },
    enabled: view === "table",
  });
  const boardTerm = useDebounced(boardSearch.trim());
  const board = useQuery({
    queryKey: ["opportunities", "board", boardTerm, ownerFilter],
    queryFn: () =>
      getList<OpportunityRecord>("/opportunities", {
        search: boardTerm || undefined,
        ownerId: ownerFilter || undefined,
        size: BOARD_SIZE,
        sort: "updatedAt,desc",
      }),
    enabled: view === "pipeline",
  });
  const totals = useQuery({
    queryKey: ["reports", "pipeline"],
    queryFn: async () => (await api.get<OppPipelineRow[]>("/reports/pipeline")).data,
  });

  useEffect(() => {
    setDraft(selected ?? { stage: "Qualification", value: 0 });
  }, [selected, creating]);

  // Deep links: ?open=ID (search palette) and ?highlight=ID (lead conversion)
  useEffect(() => {
    const id = params.get("open") ?? params.get("highlight");
    if (!id) return;
    api
      .get<OpportunityRecord>(`/opportunities/${id}`)
      .then((r) => setSelected(r.data))
      .catch((e) => toast.error(e instanceof Error ? e.message : "Deal not found"));
    params.delete("open");
    params.delete("highlight");
    setParams(params, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const companyName = (id: number | null) => companies.data?.data.find((c) => c.id === id)?.name;
  const stageDefault = (s: string | undefined) => (s ? settings.data?.stageProbabilities[s] : undefined);
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["opportunities"] });
    qc.invalidateQueries({ queryKey: ["reports"] });
  };

  /** Won/Lost always carries a reason — asked here, enforced by the API. */
  async function closeReasonFor(to: string, name: string): Promise<string | null | undefined> {
    if (!CLOSED_STAGES.includes(to)) return undefined;
    return askReason({
      title: to === "Won" ? `Mark "${name}" as won` : `Mark "${name}" as lost`,
      description:
        to === "Won" ? "What made the customer choose us? This feeds win/loss reporting." : "Why did we lose this deal? Be specific — price, timing, competitor…",
      label: to === "Won" ? "Win reason" : "Loss reason",
      confirmLabel: to === "Won" ? "Mark as won" : "Mark as lost",
      danger: to === "Lost",
    });
  }

  async function moveStage(id: number, _from: string, to: string) {
    const deal = board.data?.data.find((o) => o.id === id);
    const reason = await closeReasonFor(to, deal?.name ?? "this deal");
    if (reason === null) return; // cancelled
    try {
      await api.patch(`/opportunities/${id}`, reason ? { stage: to, closeReason: reason } : { stage: to });
      invalidate();
      toast.success(`Moved to ${to}`);
    } catch (e) {
      toast.error(saveErrorMessage(e, "deal"));
    }
  }

  const save = useMutation({
    mutationFn: async () => {
      if (!selected) {
        const reason = await closeReasonFor(draft.stage ?? "Qualification", draft.name ?? "this deal");
        if (reason === null) return null;
        const body = { ...draft, ...(reason ? { closeReason: reason } : {}) };
        return (await api.post<OpportunityRecord>("/opportunities", body)).data;
      }
      const changed: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(draft)) {
        if (v !== (selected as unknown as Record<string, unknown>)[k]) changed[k] = v;
      }
      if (changed.stage && changed.stage !== selected.stage) {
        const reason = await closeReasonFor(String(changed.stage), selected.name);
        if (reason === null) return null;
        if (reason) changed.closeReason = reason;
      }
      if (selected.version !== undefined) changed.version = selected.version;
      return (await api.patch<OpportunityRecord>(`/opportunities/${selected.id}`, changed)).data;
    },
    onSuccess: (res) => {
      if (!res) return;
      toast.success(selected ? "Deal updated" : "Deal created");
      invalidate();
      closeDrawers();
    },
    onError: (e: Error) => toast.error(saveErrorMessage(e, "deal")),
  });

  const remove = useMutation({
    mutationFn: async ({ id, reason }: { id: number; reason: string }) =>
      api.delete(`/opportunities/${id}`, { params: { reason } }),
    onSuccess: () => {
      toast.success("Deal moved to trash");
      invalidate();
      closeDrawers();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const stageColumns: KanbanColumnDef[] = OPP_STAGES.map((s) => ({ key: s, label: s, accent: STAGE_ACCENTS[s] }));
  const stageItems: Record<string, OpportunityRecord[]> = useMemo(() => {
    const map: Record<string, OpportunityRecord[]> = {};
    for (const s of OPP_STAGES) map[s] = [];
    for (const o of board.data?.data ?? []) (map[o.stage] ??= []).push(o);
    return map;
  }, [board.data]);

  const columns: Column<OpportunityRecord>[] = [
    {
      key: "name",
      header: "Deal",
      sortKey: "name",
      render: (r) => (
        <div className="min-w-0">
          <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
            {r.name}
          </p>
          <p className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
            {companyName(r.companyId) ?? "—"}
          </p>
        </div>
      ),
    },
    { key: "stage", header: "Stage", sortKey: "stage", render: (r) => <StageBadge value={r.stage} /> },
    {
      key: "value",
      header: "Value",
      align: "right",
      sortKey: valueHidden ? undefined : "value",
      render: (r) => <span className="text-sm font-semibold tabular-nums">{r.value == null ? "—" : formatCompactCurrency(r.value)}</span>,
    },
    {
      key: "probability",
      header: "Win %",
      align: "right",
      hideOnMobile: true,
      sortKey: valueHidden ? undefined : "probability",
      render: (r) => (
        <span className="text-sm tabular-nums" title={r.probabilityOverridden ? "Set by hand" : "From stage"}>
          {r.probability == null ? "—" : `${r.probability}%`}
          {r.probabilityOverridden ? " ✎" : ""}
        </span>
      ),
    },
    {
      key: "closeDate",
      header: "Close date",
      sortKey: "closeDate",
      hideOnMobile: true,
      render: (r) => <span className="text-sm">{formatDate(r.closeDate)}</span>,
    },
    {
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

  function closeDrawers() {
    setSelected(null);
    setCreating(false);
  }

  const openPipeline = (totals.data ?? []).filter((t) => !CLOSED_STAGES.includes(t.stage));
  const openCount = openPipeline.reduce((s, t) => s + t.count, 0);
  const openValue = openPipeline.reduce((s, t) => s + (t.value ?? 0), 0);
  const won = totals.data?.find((t) => t.stage === "Won");
  const activeQuery = view === "pipeline" ? board : table.query;
  const boardTruncated = (board.data?.count ?? 0) > (board.data?.data.length ?? 0);

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <PageHeader
        title="Opportunities"
        description={
          totals.data
            ? `${openCount.toLocaleString()} open deals${valueHidden ? "" : ` · ${formatCompactCurrency(openValue)} pipeline`}${
                won ? ` · ${won.count} won` : ""
              }`
            : "Every deal from qualification to close"
        }
        icon={<Handshake size={20} />}
        actions={
          <>
            {manager && (
              <button className="btn-secondary" onClick={() => setSettingsOpen(true)} title="Stage probabilities and big-deal alerts">
                <SlidersHorizontal size={15} /> <span className="hidden md:inline">Pipeline settings</span>
              </button>
            )}
            <ExportButton type="opportunities" />
            {!readOnly && role !== "INSIDE_SALES" && (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> <span className="hidden sm:inline">New deal</span>
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
                { value: "pipeline", label: "Pipeline" },
                { value: "table", label: "Table" },
              ]}
            />
            {view === "table" && (
              <FilterSelect ariaLabel="Stage" allLabel="All stages" value={stage} onChange={setStage} options={OPP_STAGES} />
            )}
            {manager && (
              <FilterSelect
                ariaLabel="Owner"
                allLabel="All owners"
                value={ownerFilter}
                onChange={setOwnerFilter}
                options={(users.data ?? []).map((u) => ({ value: String(u.id), label: u.name }))}
              />
            )}
          </>
        }
        right={
          view === "table" ? (
            <SearchInput value={table.search} onChange={table.setSearch} placeholder="Search deals…" className="w-full sm:w-64" />
          ) : (
            <SearchInput value={boardSearch} onChange={setBoardSearch} placeholder="Search deals…" className="w-full sm:w-64" />
          )
        }
      />

      {activeQuery.isError ? (
        <div className="glass-card">
          <QueryError error={activeQuery.error} onRetry={() => activeQuery.refetch()} title="Couldn't load deals" />
        </div>
      ) : view === "pipeline" ? (
        <div className="flex-1 min-h-[520px] flex flex-col gap-2">
          {boardTruncated && (
            <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
              Showing the {BOARD_SIZE} most recently updated of {board.data?.count.toLocaleString()} deals — search or switch to the table to see the rest.
            </p>
          )}
          <Kanban
            columns={stageColumns}
            itemsByColumn={stageItems}
            onMove={moveStage}
            draggable={!readOnly && role !== "INSIDE_SALES"}
            loading={board.isLoading}
            renderCard={(o) => (
              <div onClick={() => setSelected(o)}>
                <OpportunityKanbanCard opp={o} companyName={companyName(o.companyId)} ownerName={userName(o.ownerId)} />
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
          emptyTitle={table.search || stage ? "No deals match" : "No opportunities yet"}
          emptyDescription={table.search || stage ? "Try another search or clear the filters." : "Convert a lead or create a deal directly."}
        />
      )}

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.name : "New opportunity"}
        subtitle={selected ? `Updated ${formatDateTime(selected.updatedAt)}` : "Add a deal to the pipeline"}
        width="36rem"
        headerExtra={
          selected &&
          manager && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              title="Move to trash"
              onClick={async () => {
                const reason = await askReason({
                  title: "Move this deal to trash?",
                  description: `"${selected.name}" can be restored from Trash later.`,
                  confirmLabel: "Move to trash",
                  danger: true,
                });
                if (reason) remove.mutate({ id: selected.id, reason });
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        {selected?.closeReason && CLOSED_STAGES.includes(selected.stage) && (
          <div
            className="mb-4 rounded-lg px-3 py-2.5 text-sm flex items-start gap-2"
            style={{
              backgroundColor: selected.stage === "Won" ? "rgb(12 163 12 / 0.1)" : "rgb(208 59 59 / 0.08)",
              color: "var(--ink-secondary)",
            }}
          >
            {selected.stage === "Won" ? (
              <Trophy size={16} className="shrink-0 mt-0.5" style={{ color: "var(--good)" }} />
            ) : (
              <XCircle size={16} className="shrink-0 mt-0.5" style={{ color: "var(--critical)" }} />
            )}
            <span>
              <strong style={{ color: "var(--ink-primary)" }}>
                {selected.stage} {selected.closedAt ? `on ${formatDate(selected.closedAt)}` : ""}:
              </strong>{" "}
              {selected.closeReason}
            </span>
          </div>
        )}
        <OpportunityForm
          value={draft}
          onChange={(p) => setDraft((d) => ({ ...d, ...p }))}
          canEditProbability={manager}
          canEditPoStatus={manager}
          stageDefault={stageDefault(draft.stage)}
          valueHidden={valueHidden}
        />
        {selected && (
          <div className="mt-5 pt-4 hairline border-t space-y-5">
            <RecordPagePanel relatedType="Opportunity" relatedId={selected.id} readOnly={readOnly} />
            <AttachmentList relatedType="Opportunity" relatedId={selected.id} />
          </div>
        )}
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          {!readOnly && role !== "INSIDE_SALES" && (
            <button className="btn-primary" disabled={!draft.name || save.isPending} onClick={() => save.mutate()}>
              {save.isPending ? "Saving…" : selected ? "Save changes" : "Create deal"}
            </button>
          )}
        </div>
      </Drawer>
      <PipelineSettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} />
      {reasonNode}
    </div>
  );
}
