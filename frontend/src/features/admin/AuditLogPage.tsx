import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Download, Filter, History, RotateCcw, ShieldOff } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api, ApiError, downloadFile } from "@/lib/api";
import { isManagerUp, useAuthStore } from "@/lib/auth";
import { formatDateTime, formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { QueryError } from "@/components/ui/QueryError";
import { SearchInput } from "@/components/ui/SearchInput";
import { Skeleton, SkeletonRows } from "@/components/ui/Skeleton";
import {
  actionTone,
  diffFields,
  EMPTY_FILTERS,
  filterParams,
  humanize,
  inline,
  parseJson,
  pretty,
  toAuditPage,
  type AuditEventRow,
  type AuditFacets,
  type AuditFilters,
  type Tone,
} from "./auditDiff";

const PAGE_SIZES = [25, 50, 100];

const TONE_CLASS: Record<Tone, string> = {
  good: "badge-good",
  critical: "badge-critical",
  warning: "badge-warning",
  brand: "badge-brand",
  neutral: "badge-neutral",
};

function ActionBadge({ action }: { action: string }) {
  return <span className={TONE_CLASS[actionTone(action)]}>{humanize(action)}</span>;
}

function useDebounced<T>(value: T, ms = 350): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

export default function AuditLogPage() {
  const role = useAuthStore((s) => s.user?.role);
  const allowed = isManagerUp(role);

  const [filters, setFilters] = useState<AuditFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [exporting, setExporting] = useState(false);
  const [showFilters, setShowFilters] = useState(false);

  const debouncedQ = useDebounced(filters.q);
  const effective = useMemo(() => ({ ...filters, q: debouncedQ }), [filters, debouncedQ]);
  const params = useMemo(() => filterParams(effective).toString(), [effective]);

  // Any filter change goes back to the first page.
  useEffect(() => setPage(0), [params, size]);

  const list = useQuery({
    queryKey: ["audit-events", params, page, size],
    queryFn: async () => {
      const qs = new URLSearchParams(params);
      qs.set("page", String(page));
      qs.set("size", String(size));
      const res = await api.get(`/audit-events?${qs.toString()}`);
      return toAuditPage(res.data, page, size);
    },
    placeholderData: keepPreviousData,
    enabled: allowed,
  });

  const facets = useQuery({
    queryKey: ["audit-events", "facets"],
    queryFn: async () => (await api.get<AuditFacets>("/audit-events/facets")).data,
    enabled: allowed,
    staleTime: 5 * 60_000,
    retry: false,
  });
  // Actor filter is a convenience — if the people list can't load, it is simply hidden.
  const users = useQuery({
    queryKey: ["users"],
    queryFn: async () => (await api.get<{ id: number; name: string; color: string | null }[]>("/users")).data,
    enabled: allowed,
    staleTime: 5 * 60_000,
    retry: false,
  });

  const rows = list.data?.data ?? [];
  const resources = facets.data?.resources ?? Array.from(new Set(rows.map((r) => r.resource))).sort();
  const actions = facets.data?.actions ?? Array.from(new Set(rows.map((r) => r.action))).sort();
  const totalPages = list.data?.totalPages ?? 1;
  const total = list.data?.count ?? 0;
  const activeFilterCount = (Object.keys(filters) as (keyof AuditFilters)[]).filter((k) => k !== "q" && filters[k]).length;
  const hasFilters = activeFilterCount > 0 || !!filters.q;

  const set = (k: keyof AuditFilters) => (v: string) => setFilters((f) => ({ ...f, [k]: v }));

  async function exportCsv() {
    setExporting(true);
    try {
      const qs = filterParams(effective).toString();
      await downloadFile(`/audit-events/export${qs ? `?${qs}` : ""}`, "audit-log.csv");
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Export failed");
    } finally {
      setExporting(false);
    }
  }

  if (!allowed) {
    return (
      <div className="flex flex-col gap-4">
        <PageHeader title="Audit Log" icon={<History size={20} />} />
        <div className="glass-card">
          <EmptyState icon={ShieldOff} title="Managers and admins only" description="The audit log is available to Managers, VPs and Super Admins." />
        </div>
      </div>
    );
  }

  const forbidden = list.error instanceof ApiError && list.error.status === 403;
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, page * size + rows.length);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Audit Log"
        description={
          role === "MANAGER"
            ? "Changes made by people in your team · write-once record"
            : "Every change across your organization · write-once record"
        }
        icon={<History size={20} />}
        actions={
          <button className="btn-secondary" onClick={() => void exportCsv()} disabled={exporting || forbidden}>
            <Download size={15} /> {exporting ? "Exporting…" : "Export CSV"}
          </button>
        }
      />

      {/* Filters */}
      <section className="glass-card p-3 sm:p-4 space-y-3" aria-label="Filters">
        <div className="flex gap-2">
          <SearchInput value={filters.q} onChange={set("q")} placeholder="Search records, people, IPs…" className="flex-1 min-w-0" />
          <button
            className="btn-secondary md:hidden shrink-0"
            onClick={() => setShowFilters((s) => !s)}
            aria-expanded={showFilters}
            aria-controls="audit-filters"
          >
            <Filter size={15} />
            {activeFilterCount > 0 ? <span className="badge-brand">{activeFilterCount}</span> : "Filters"}
          </button>
          {hasFilters && (
            <button className="btn-ghost shrink-0 hidden md:inline-flex" onClick={() => setFilters(EMPTY_FILTERS)}>
              <RotateCcw size={14} /> Reset
            </button>
          )}
        </div>
        <div id="audit-filters" className={`${showFilters ? "grid" : "hidden"} md:grid grid-cols-2 md:grid-cols-5 gap-2.5`}>
          <label className="col-span-1">
            <span className="field-label">Resource</span>
            <select className="select" value={filters.resource} onChange={(e) => set("resource")(e.target.value)}>
              <option value="">All resources</option>
              {resources.map((r) => (
                <option key={r} value={r}>
                  {humanize(r)}
                </option>
              ))}
            </select>
          </label>
          <label className="col-span-1">
            <span className="field-label">Action</span>
            <select className="select" value={filters.action} onChange={(e) => set("action")(e.target.value)}>
              <option value="">All actions</option>
              {actions.map((a) => (
                <option key={a} value={a}>
                  {humanize(a)}
                </option>
              ))}
            </select>
          </label>
          {users.isSuccess ? (
            <label className="col-span-2 md:col-span-1">
              <span className="field-label">Actor</span>
              <select className="select" value={filters.userId} onChange={(e) => set("userId")(e.target.value)}>
                <option value="">Anyone</option>
                {[...users.data]
                  .sort((a, b) => a.name.localeCompare(b.name))
                  .map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.name}
                    </option>
                  ))}
              </select>
            </label>
          ) : (
            <div className="hidden md:block" />
          )}
          <label>
            <span className="field-label">From</span>
            <input type="date" className="input" value={filters.from} max={filters.to || undefined} onChange={(e) => set("from")(e.target.value)} />
          </label>
          <label>
            <span className="field-label">To</span>
            <input type="date" className="input" value={filters.to} min={filters.from || undefined} onChange={(e) => set("to")(e.target.value)} />
          </label>
          {hasFilters && (
            <button className="btn-ghost col-span-2 md:hidden" onClick={() => setFilters(EMPTY_FILTERS)}>
              <RotateCcw size={14} /> Reset filters
            </button>
          )}
        </div>
      </section>

      {/* Results */}
      {list.isError ? (
        <div className="glass-card">
          {forbidden ? (
            <EmptyState icon={ShieldOff} title="You don't have access to the audit log" description="Ask a Super Admin if you need to review changes." />
          ) : (
            <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load audit events" />
          )}
        </div>
      ) : list.isLoading ? (
        <div className="glass-card overflow-hidden">
          <SkeletonRows rows={8} cols={5} />
        </div>
      ) : rows.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={History}
            title={hasFilters ? "No events match these filters" : "No audit events yet"}
            description={hasFilters ? "Widen the date range or clear a filter." : "Changes to records will appear here as they happen."}
            action={
              hasFilters && (
                <button className="btn-secondary" onClick={() => setFilters(EMPTY_FILTERS)}>
                  <RotateCcw size={14} /> Reset filters
                </button>
              )
            }
          />
        </div>
      ) : (
        <div className={`glass-card overflow-hidden transition-opacity ${list.isFetching && list.isPlaceholderData ? "opacity-60" : ""}`}>
          {/* Table on md+ */}
          <div className="hidden md:block overflow-x-auto thin-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>When</th>
                  <th>Action</th>
                  <th>Resource</th>
                  <th>Record</th>
                  <th>Actor</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr
                    key={r.id}
                    onClick={() => setSelectedId(r.id)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        setSelectedId(r.id);
                      }
                    }}
                    tabIndex={0}
                    className="focus-brand"
                    aria-label={`${humanize(r.action)} ${r.resource} ${r.resourceName ?? r.resourceId ?? ""} — open details`}
                  >
                    <td className="whitespace-nowrap" title={formatDateTime(r.createdAt)}>
                      <span className="text-sm" style={{ color: "var(--ink-primary)" }}>
                        {formatDateTime(r.createdAt)}
                      </span>
                    </td>
                    <td>
                      <ActionBadge action={r.action} />
                    </td>
                    <td className="text-sm capitalize" style={{ color: "var(--ink-secondary)" }}>
                      {humanize(r.resource)}
                    </td>
                    <td className="max-w-[18rem]">
                      <span className="text-sm truncate block" style={{ color: "var(--ink-primary)" }}>
                        {r.resourceName ?? (r.resourceId ? `#${r.resourceId}` : "—")}
                      </span>
                    </td>
                    <td>
                      <span className="inline-flex items-center gap-2 text-sm" style={{ color: "var(--ink-secondary)" }}>
                        <Avatar name={r.userName ?? "System"} color={null} size={22} />
                        {r.userName ?? "System"}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Cards on phones */}
          <ul className="md:hidden divide-y" style={{ borderColor: "rgb(var(--panel-border) / 0.06)" }}>
            {rows.map((r) => (
              <li key={r.id}>
                <button className="w-full text-left px-4 py-3.5 focus-brand min-h-[44px]" onClick={() => setSelectedId(r.id)}>
                  <div className="flex items-center justify-between gap-2">
                    <ActionBadge action={r.action} />
                    <span className="text-xs shrink-0" style={{ color: "var(--ink-muted)" }} title={formatDateTime(r.createdAt)}>
                      {formatRelative(r.createdAt)}
                    </span>
                  </div>
                  <p className="text-sm font-medium mt-1.5 truncate" style={{ color: "var(--ink-primary)" }}>
                    {r.resourceName ?? (r.resourceId ? `#${r.resourceId}` : "—")}
                  </p>
                  <p className="text-xs mt-0.5 truncate capitalize" style={{ color: "var(--ink-muted)" }}>
                    {humanize(r.resource)} · {r.userName ?? "System"}
                  </p>
                </button>
              </li>
            ))}
          </ul>

          {/* Pagination */}
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 px-4 py-3 hairline border-t">
            <p className="text-xs" style={{ color: "var(--ink-muted)" }} aria-live="polite">
              {first.toLocaleString()}–{last.toLocaleString()} of {total.toLocaleString()} events
            </p>
            <div className="flex items-center gap-2 justify-between sm:justify-end">
              <label className="flex items-center gap-2 text-xs" style={{ color: "var(--ink-muted)" }}>
                <span className="hidden sm:inline">Rows</span>
                <select className="select h-9 py-1 w-[4.5rem]" value={size} onChange={(e) => setSize(Number(e.target.value))} aria-label="Rows per page">
                  {PAGE_SIZES.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </label>
              <div className="flex items-center gap-1.5">
                <button className="pager-btn" onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page === 0 || list.isFetching} aria-label="Previous page">
                  <ChevronLeft size={16} />
                </button>
                <span className="text-xs font-medium px-1 tabular-nums whitespace-nowrap" style={{ color: "var(--ink-secondary)" }}>
                  Page {page + 1} of {totalPages}
                </span>
                <button
                  className="pager-btn"
                  onClick={() => setPage((p) => p + 1)}
                  disabled={page + 1 >= totalPages || list.isFetching}
                  aria-label="Next page"
                >
                  <ChevronRight size={16} />
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      <EventDrawer id={selectedId} initial={rows.find((r) => r.id === selectedId) ?? null} onClose={() => setSelectedId(null)} />
    </div>
  );
}

function EventDrawer({ id, initial, onClose }: { id: number | null; initial: AuditEventRow | null; onClose: () => void }) {
  const [view, setView] = useState<"changes" | "raw">("changes");
  const q = useQuery({
    queryKey: ["audit-events", "detail", id],
    queryFn: async () => (await api.get<AuditEventRow>(`/audit-events/${id}`)).data,
    enabled: id != null,
    // The list row already carries the snapshots; show it straight away and refresh.
    placeholderData: initial ?? undefined,
  });
  const ev = q.data;

  const before = useMemo(() => parseJson(ev?.beforeJson), [ev?.beforeJson]);
  const after = useMemo(() => parseJson(ev?.afterJson), [ev?.afterJson]);
  const changes = useMemo(() => diffFields(before, after), [before, after]);
  const hasSnapshots = before != null || after != null;

  return (
    <Drawer
      open={id != null}
      onClose={onClose}
      title={ev ? `${humanize(ev.action)} · ${humanize(ev.resource)}` : "Audit event"}
      subtitle={ev ? formatDateTime(ev.createdAt) : undefined}
      width="40rem"
    >
      {q.isError && !ev ? (
        <QueryError error={q.error} onRetry={() => q.refetch()} title="Couldn't load this event" />
      ) : !ev ? (
        <div className="space-y-3">
          <Skeleton className="h-5 w-1/2" />
          <Skeleton className="h-4 w-1/3" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : (
        <div className="space-y-4">
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 text-sm">
            <Meta label="Action">
              <ActionBadge action={ev.action} />
            </Meta>
            <Meta label="Resource">
              <span className="capitalize">{humanize(ev.resource)}</span>
            </Meta>
            <Meta label="Record">
              {ev.resourceName ?? "—"}
              {ev.resourceId && (
                <span className="ml-1.5 font-mono text-xs" style={{ color: "var(--ink-muted)" }}>
                  #{ev.resourceId}
                </span>
              )}
            </Meta>
            <Meta label="Actor">{ev.userName ?? "System"}</Meta>
            <Meta label="IP address">
              <span className="font-mono text-xs">{ev.ipAddress ?? "—"}</span>
            </Meta>
            <Meta label="Event id">
              <span className="font-mono text-xs">{ev.id}</span>
            </Meta>
            {ev.userAgent && (
              <div className="sm:col-span-2">
                <dt className="field-label">User agent</dt>
                <dd className="text-xs break-words" style={{ color: "var(--ink-secondary)" }}>
                  {ev.userAgent}
                </dd>
              </div>
            )}
          </dl>

          {!hasSnapshots ? (
            <p className="text-sm rounded-xl px-4 py-3" style={{ backgroundColor: "var(--surface-sunken)", color: "var(--ink-muted)" }}>
              No before/after snapshot was recorded for this event.
            </p>
          ) : (
            <section className="space-y-3">
              <div className="flex items-center justify-between gap-2">
                <h3 className="section-title">What changed</h3>
                {changes && (
                  <div className="inline-flex p-0.5 rounded-lg glass-solid text-xs" role="group" aria-label="Diff view">
                    {(["changes", "raw"] as const).map((v) => (
                      <button
                        key={v}
                        className="px-2.5 py-1 rounded-md font-medium"
                        aria-pressed={view === v}
                        style={{
                          backgroundColor: view === v ? "rgb(var(--ring-brand) / 0.14)" : undefined,
                          color: view === v ? "var(--brand-600)" : "var(--ink-secondary)",
                        }}
                        onClick={() => setView(v)}
                      >
                        {v === "changes" ? "Fields" : "Raw JSON"}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {changes && view === "changes" ? (
                changes.length === 0 ? (
                  <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
                    The snapshots are identical.
                  </p>
                ) : (
                  <div className="rounded-xl overflow-hidden border hairline">
                    <div className="overflow-x-auto thin-scroll">
                      <table className="w-full text-xs">
                        <thead>
                          <tr style={{ backgroundColor: "var(--surface-sunken)", color: "var(--ink-muted)" }}>
                            <th className="text-left font-semibold uppercase tracking-wide px-3 py-2 w-1/4">Field</th>
                            <th className="text-left font-semibold uppercase tracking-wide px-3 py-2">Before</th>
                            <th className="text-left font-semibold uppercase tracking-wide px-3 py-2">After</th>
                          </tr>
                        </thead>
                        <tbody>
                          {changes.map((c) => (
                            <tr key={c.key} className="hairline border-t align-top">
                              <td className="px-3 py-2 font-mono font-medium break-all" style={{ color: "var(--ink-primary)" }}>
                                {c.key}
                              </td>
                              <td className="px-3 py-2">
                                {c.kind !== "added" && (
                                  <span className="diff-removed rounded px-1.5 py-0.5 font-mono break-all whitespace-pre-wrap inline-block max-w-full">
                                    {inline(c.before)}
                                  </span>
                                )}
                              </td>
                              <td className="px-3 py-2">
                                {c.kind !== "removed" && (
                                  <span className="diff-added rounded px-1.5 py-0.5 font-mono break-all whitespace-pre-wrap inline-block max-w-full">
                                    {inline(c.after)}
                                  </span>
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )
              ) : (
                <div className="grid grid-cols-1 gap-3">
                  <div>
                    <p className="field-label">Before</p>
                    <pre className="code-block max-h-72">{pretty(before)}</pre>
                  </div>
                  <div>
                    <p className="field-label">After</p>
                    <pre className="code-block max-h-72">{pretty(after)}</pre>
                  </div>
                </div>
              )}
            </section>
          )}
        </div>
      )}
    </Drawer>
  );
}

function Meta({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="field-label">{label}</dt>
      <dd className="truncate" style={{ color: "var(--ink-primary)" }}>
        {children}
      </dd>
    </div>
  );
}
