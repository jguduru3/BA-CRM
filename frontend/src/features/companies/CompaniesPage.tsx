import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Building2, Globe, Plus, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { api, ApiError } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { useUsersLookup } from "@/lib/hooks";
import { formatCompactCurrency, formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Company } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StageBadge } from "@/components/ui/Badge";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { QueryError } from "@/components/ui/QueryError";
import { useReason } from "@/components/ui/ReasonDialog";
import { safeHref } from "@/components/ui/SafeLink";
import { SearchInput } from "@/components/ui/SearchInput";
import { changedFields, ExportButton, FilterSelect, ListToolbar, saveErrorMessage } from "@/features/crm/ListParts";
import { useServerList } from "@/features/crm/useServerList";
import { CompanyDraft, CompanyForm, COMPANY_STATUSES } from "./CompanyForm";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { RecordPagePanel } from "@/features/recordpages/RecordPagePanel";
import { DuplicateDialog } from "@/features/duplicates/DuplicateDialog";
import { isDuplicateError, saveChecked } from "@/features/duplicates/duplicates";
import type { DuplicateError } from "@/features/duplicates/duplicates";

export default function CompaniesPage() {
  const [params, setParams] = useSearchParams();
  const [status, setStatus] = useState("");
  const [selected, setSelected] = useState<Company | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<CompanyDraft>({});
  const users = useUsersLookup();
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();
  const { askReason, node: reasonNode } = useReason();
  const [duplicate, setDuplicate] = useState<DuplicateError | null>(null);
  const role = useAuthStore((s) => s.user?.role);
  const readOnly = isReadOnly(role);
  const manager = isManagerUp(role);

  const table = useServerList<Company>("companies", {
    filters: { status },
    defaultSort: { key: "name", dir: "asc" },
  });

  useEffect(() => {
    setDraft(selected ?? { status: "Prospect", revenue: 0 });
  }, [selected, creating]);

  useEffect(() => {
    const id = params.get("open");
    if (!id) return;
    api
      .get<Company>(`/companies/${id}`)
      .then((r) => {
        setCreating(false);
        setSelected(r.data);
      })
      .catch((e) => toast.error(e instanceof Error ? e.message : "Company not found"));
    params.delete("open");
    setParams(params, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params.get("open")]);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["companies"] });
    qc.invalidateQueries({ queryKey: ["contacts"] });
    qc.invalidateQueries({ queryKey: ["opportunities"] });
    qc.invalidateQueries({ queryKey: ["leads"] });
  };
  const closeDrawers = () => {
    setSelected(null);
    setCreating(false);
  };

  const save = useMutation({
    mutationFn: async (allowDuplicate: boolean = false) =>
      selected
        ? saveChecked<Company>("patch", `/companies/${selected.id}`, changedFields(selected, draft), "company", allowDuplicate)
        : saveChecked<Company>("post", "/companies", draft, "company", allowDuplicate),
    onSuccess: () => {
      setDuplicate(null);
      toast.success(selected ? "Company updated" : "Company created");
      invalidate();
      closeDrawers();
    },
    onError: (e: Error) => (isDuplicateError(e) ? setDuplicate(e) : toast.error(saveErrorMessage(e, "company"))),
  });

  /**
   * Delete: the API refuses (409 HAS_CHILDREN) while contacts / deals / leads are
   * still attached. Managers can then move everything to trash together.
   */
  async function deleteCompany(company: Company) {
    const reason = await askReason({
      title: `Move "${company.name}" to trash?`,
      description: "It can be restored from Trash later.",
      confirmLabel: "Move to trash",
      danger: true,
    });
    if (!reason) return;
    try {
      await api.delete(`/companies/${company.id}`, { params: { reason } });
      toast.success("Company moved to trash");
      invalidate();
      closeDrawers();
    } catch (e) {
      if (!(e instanceof ApiError) || e.code !== "HAS_CHILDREN") {
        toast.error(e instanceof Error ? e.message : "Could not delete");
        return;
      }
      if (!manager) {
        toast.error(`${e.message} Ask a Manager to delete them together.`);
        return;
      }
      const ok = await confirm(
        "Delete the company and everything attached?",
        `${e.message.replace(" Move or delete them first, or delete everything together.", "")}. They'll all go to Trash, and restoring the company brings them back.`
      );
      if (!ok) return;
      try {
        await api.delete(`/companies/${company.id}`, { params: { reason, cascade: true } });
        toast.success("Company and its records moved to trash");
        invalidate();
        closeDrawers();
      } catch (e2) {
        toast.error(e2 instanceof Error ? e2.message : "Could not delete");
      }
    }
  }

  const columns: Column<Company>[] = [
    {
      key: "name",
      header: "Company",
      sortKey: "name",
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
    { key: "status", header: "Status", sortKey: "status", render: (r) => <StageBadge value={r.status} /> },
    { key: "size", header: "Size", hideOnMobile: true, render: (r) => <span className="text-sm">{r.size ?? "—"}</span> },
    { key: "location", header: "Location", sortKey: "location", hideOnMobile: true, render: (r) => <span className="text-sm">{r.location ?? "—"}</span> },
    {
      key: "revenue",
      header: "Revenue",
      align: "right",
      sortKey: "revenue",
      hideOnMobile: true,
      render: (r) => <span className="text-sm font-semibold tabular-nums">{formatCompactCurrency(r.revenue)}</span>,
    },
    {
      key: "website",
      header: "Website",
      hideOnMobile: true,
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

  const filtered = !!(table.search || status);

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Companies"
        description={table.query.data ? `${table.total.toLocaleString()} accounts` : "The accounts your leads and deals belong to"}
        icon={<Building2 size={20} />}
        actions={
          <>
            <ExportButton type="companies" />
            {!readOnly && (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> <span className="hidden sm:inline">New company</span>
              </button>
            )}
          </>
        }
      />
      <ListToolbar
        left={<FilterSelect ariaLabel="Status" allLabel="All statuses" value={status} onChange={setStatus} options={COMPANY_STATUSES} />}
        right={<SearchInput value={table.search} onChange={table.setSearch} placeholder="Search name, industry, city…" className="w-full sm:w-72" />}
      />

      {table.query.isError ? (
        <div className="glass-card">
          <QueryError error={table.query.error} onRetry={() => table.query.refetch()} title="Couldn't load companies" />
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
          emptyTitle={filtered ? "No companies match" : "No companies yet"}
          emptyDescription={filtered ? "Try another search or clear the filter." : "Add the accounts your leads and deals belong to."}
          emptyAction={
            !filtered && !readOnly ? (
              <button className="btn-primary" onClick={() => setCreating(true)}>
                <Plus size={15} /> New company
              </button>
            ) : undefined
          }
        />
      )}

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.name : "New company"}
        subtitle={selected ? `Updated ${formatDateTime(selected.updatedAt)}` : "Add an account"}
        headerExtra={
          selected &&
          manager && (
            <button className="btn-icon" style={{ color: "var(--critical)" }} title="Move to trash" onClick={() => deleteCompany(selected)}>
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <CompanyForm value={draft} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} />
        {selected && (
          <div className="mt-5 pt-4 hairline border-t space-y-5">
            <RecordPagePanel relatedType="Company" relatedId={selected.id} readOnly={readOnly} />
            <AttachmentList relatedType="Company" relatedId={selected.id} />
          </div>
        )}
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          {!readOnly && (
            <button className="btn-primary" disabled={!draft.name || save.isPending} onClick={() => save.mutate(false)}>
              {save.isPending ? "Saving…" : selected ? "Save changes" : "Create company"}
            </button>
          )}
        </div>
      </Drawer>
      {confirmNode}
      {reasonNode}
      <DuplicateDialog
        error={duplicate}
        saving={save.isPending}
        onClose={() => setDuplicate(null)}
        onSaveAnyway={() => save.mutate(true)}
      />
    </div>
  );
}
