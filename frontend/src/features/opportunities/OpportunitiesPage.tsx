import { useQueryClient } from "@tanstack/react-query";
import { Handshake, Plus, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api } from "@/lib/api";
import { useCompaniesLookup, useEntityCrud, useUsersLookup } from "@/lib/hooks";
import { formatCompactCurrency, formatDate } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Opportunity } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Kanban, KanbanColumnDef } from "@/components/ui/Kanban";
import { SearchInput } from "@/components/ui/SearchInput";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { OppDraft, OpportunityForm, OPP_STAGES } from "./OpportunityForm";
import { OpportunityKanbanCard } from "./OpportunityKanbanCard";

const STAGE_ACCENTS: Record<string, string> = {
  Qualification: "#2a78d6",
  Proposal: "#eb6834",
  Negotiation: "#e87ba4",
  "Verbal Commit": "#eda100",
  Won: "#0ca30c",
  Lost: "#d03b3b",
};

export default function OpportunitiesPage() {
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState<"pipeline" | "table">("pipeline");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<Opportunity | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<OppDraft>({});
  const users = useUsersLookup();
  const companies = useCompaniesLookup();
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const { list, create, update, remove } = useEntityCrud<Opportunity>("opportunities", {
    params: { search, limit: 500 },
    label: "Opportunity",
  });

  useEffect(() => {
    setDraft(selected ?? { stage: "Qualification", value: 0, probability: 50 });
  }, [selected, creating]);

  useEffect(() => {
    const id = params.get("highlight");
    if (id && list.data) {
      const o = list.data.data.find((x) => x.id === Number(id));
      if (o) {
        setSelected(o);
        params.delete("highlight");
        setParams(params, { replace: true });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list.data]);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const companyName = (id: number | null) => companies.data?.data.find((c) => c.id === id)?.name;

  const stageColumns: KanbanColumnDef[] = OPP_STAGES.map((s) => ({ key: s, label: s, accent: STAGE_ACCENTS[s] }));
  const stageItems: Record<string, Opportunity[]> = useMemo(() => {
    const map: Record<string, Opportunity[]> = {};
    for (const s of OPP_STAGES) map[s] = [];
    for (const o of list.data?.data ?? []) (map[o.stage] ??= []).push(o);
    return map;
  }, [list.data]);

  async function moveStage(id: number, _from: string, to: string) {
    try {
      await api.put(`/opportunities/${id}`, { stage: to });
      qc.invalidateQueries({ queryKey: ["opportunities"] });
      toast.success(`Moved to ${to}`);
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not move deal");
    }
  }

  const columns: Column<Opportunity>[] = [
    {
      key: "name",
      header: "Deal",
      sortValue: (r) => r.name,
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
    { key: "stage", header: "Stage", sortValue: (r) => r.stage, render: (r) => <StageBadge value={r.stage} /> },
    {
      key: "value",
      header: "Value",
      align: "right",
      sortValue: (r) => r.value,
      render: (r) => <span className="text-sm font-semibold tabular-nums">{formatCompactCurrency(r.value)}</span>,
    },
    {
      key: "probability",
      header: "Probability",
      align: "right",
      sortValue: (r) => r.probability,
      render: (r) => <span className="text-sm tabular-nums">{r.probability}%</span>,
    },
    {
      key: "closeDate",
      header: "Close date",
      sortValue: (r) => r.closeDate ?? "",
      render: (r) => <span className="text-sm">{formatDate(r.closeDate)}</span>,
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

  const closeDrawers = () => {
    setSelected(null);
    setCreating(false);
  };

  const save = () => {
    if (selected) {
      update.mutate({ id: selected.id, payload: draft }, { onSuccess: closeDrawers });
    } else {
      create.mutate(draft, { onSuccess: closeDrawers });
    }
  };

  const totalOpen = (list.data?.data ?? [])
    .filter((o) => o.stage !== "Won" && o.stage !== "Lost")
    .reduce((s, o) => s + o.value, 0);

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <PageHeader
        title="Opportunities"
        description={`${list.data?.count ?? 0} deals · ${formatCompactCurrency(totalOpen)} open pipeline`}
        icon={<Handshake size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New opportunity
          </button>
        }
      />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs
          value={view}
          onChange={setView}
          tabs={[
            { value: "pipeline", label: "Pipeline" },
            { value: "table", label: "Table" },
          ]}
        />
        <SearchInput value={search} onChange={setSearch} placeholder="Search deals…" className="w-full sm:w-64" />
      </div>

      {view === "pipeline" ? (
        <div className="flex-1 min-h-[520px]">
          <Kanban
            columns={stageColumns}
            itemsByColumn={stageItems}
            onMove={moveStage}
            loading={list.isLoading}
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
          rows={list.data?.data ?? []}
          loading={list.isLoading}
          onRowClick={setSelected}
          emptyTitle="No opportunities yet"
          emptyDescription="Convert a lead or create a deal directly."
        />
      )}

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.name : "New opportunity"}
        headerExtra={
          selected && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              title="Move to trash"
              onClick={async () => {
                const ok = await confirm("Delete this opportunity?", `"${selected.name}" will be moved to trash.`);
                if (ok) remove.mutate({ id: selected.id, reason: "Deleted from opportunity panel" }, { onSuccess: closeDrawers });
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <OpportunityForm value={draft} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          <button className="btn-primary" disabled={!draft.name} onClick={save}>
            {selected ? "Save changes" : "Create opportunity"}
          </button>
        </div>
      </Drawer>
      {confirmNode}
    </div>
  );
}
