import { Building2, Globe, Plus, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useUsersLookup } from "@/lib/hooks";
import { useEntityCrud } from "@/lib/hooks";
import { formatCompactCurrency } from "@/lib/format";
import type { Company } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { SearchInput } from "@/components/ui/SearchInput";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { CompanyDraft, CompanyForm } from "./CompanyForm";
import { safeHref } from "@/components/ui/SafeLink";

export default function CompaniesPage() {
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<Company | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<CompanyDraft>({});
  const users = useUsersLookup();
  const { confirm, node: confirmNode } = useConfirm();

  const { list, create, update, remove } = useEntityCrud<Company>("companies", {
    params: { search, limit: 300 },
    label: "Company",
  });

  useEffect(() => {
    setDraft(selected ?? { status: "Prospect", revenue: 0 });
  }, [selected, creating]);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;

  const columns: Column<Company>[] = [
    {
      key: "name",
      header: "Company",
      sortValue: (r) => r.name,
      render: (r) => (
        <div className="flex items-center gap-2.5 min-w-0">
          <div
            className="h-8 w-8 rounded-lg flex items-center justify-center shrink-0"
            style={{ backgroundColor: "rgb(var(--ring-brand) / 0.12)" }}
          >
            <Building2 size={15} style={{ color: "var(--brand-600)" }} />
          </div>
          <div className="min-w-0">
            <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
              {r.name}
            </p>
            <p className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
              {r.industry ?? "—"}
            </p>
          </div>
        </div>
      ),
    },
    { key: "status", header: "Status", sortValue: (r) => r.status, render: (r) => <StageBadge value={r.status} /> },
    { key: "size", header: "Size", render: (r) => <span className="text-sm">{r.size ?? "—"}</span> },
    { key: "location", header: "Location", render: (r) => <span className="text-sm">{r.location ?? "—"}</span> },
    {
      key: "revenue",
      header: "Revenue",
      align: "right",
      sortValue: (r) => r.revenue ?? 0,
      render: (r) => <span className="text-sm font-semibold tabular-nums">{formatCompactCurrency(r.revenue)}</span>,
    },
    {
      key: "website",
      header: "Website",
      render: (r) =>
        r.website && safeHref(r.website) ? (
          <a
            href={safeHref(r.website)!}
            target="_blank"
            rel="noopener noreferrer nofollow"
            onClick={(e) => e.stopPropagation()}
            className="inline-flex items-center gap-1 text-sm"
            style={{ color: "var(--brand-600)" }}
          >
            <Globe size={13} /> Visit
          </a>
        ) : (
          <span className="text-sm" style={{ color: "var(--ink-muted)" }}>
            —
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

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Companies"
        description={`${list.data?.count ?? 0} accounts`}
        icon={<Building2 size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New company
          </button>
        }
      />
      <SearchInput value={search} onChange={setSearch} placeholder="Search companies…" className="max-w-xs" />

      <DataTable
        columns={columns}
        rows={list.data?.data ?? []}
        loading={list.isLoading}
        onRowClick={setSelected}
        emptyTitle="No companies yet"
        emptyDescription="Add the accounts your leads and deals belong to."
        emptyAction={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New company
          </button>
        }
      />

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.name : "New company"}
        headerExtra={
          selected && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              title="Move to trash"
              onClick={async () => {
                const ok = await confirm("Delete this company?", `"${selected.name}" will be moved to trash.`);
                if (ok) remove.mutate({ id: selected.id, reason: "Deleted from company panel" }, { onSuccess: closeDrawers });
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <CompanyForm value={draft} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          <button className="btn-primary" disabled={!draft.name} onClick={save}>
            {selected ? "Save changes" : "Create company"}
          </button>
        </div>
      </Drawer>
      {confirmNode}
    </div>
  );
}
