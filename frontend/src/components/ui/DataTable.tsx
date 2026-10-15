import { ArrowDown, ArrowUp, ArrowUpDown, ChevronLeft, ChevronRight } from "lucide-react";
import { useMemo, useState } from "react";
import { EmptyState } from "./EmptyState";
import { SkeletonRows } from "./Skeleton";

export interface Column<T> {
  key: string;
  header: string;
  render: (row: T) => React.ReactNode;
  /** Client-side sort (used when the table isn't server-sorted). */
  sortValue?: (row: T) => string | number | null | undefined;
  /** Server-side sort key (sent as `sort=<key>,asc|desc`) — enables sorting in server mode. */
  sortKey?: string;
  width?: string;
  align?: "left" | "right" | "center";
  /** Hide this column below the `sm` breakpoint to keep tables readable on phones. */
  hideOnMobile?: boolean;
}

export interface ServerSort {
  key: string;
  dir: "asc" | "desc";
}

export interface Pagination {
  page: number; // 0-based
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
}

export function sortParam(sort: ServerSort | null | undefined): string | undefined {
  return sort ? `${sort.key},${sort.dir}` : undefined;
}

export function DataTable<T extends { id: number }>({
  columns,
  rows,
  loading,
  onRowClick,
  emptyTitle = "Nothing here yet",
  emptyDescription,
  emptyAction,
  pagination,
  sort: serverSort,
  onSortChange,
  fetching,
}: {
  columns: Column<T>[];
  rows: T[];
  loading?: boolean;
  onRowClick?: (row: T) => void;
  emptyTitle?: string;
  emptyDescription?: string;
  emptyAction?: React.ReactNode;
  /** Server-side pagination: renders the pager under the table. */
  pagination?: Pagination;
  /** Server-side sorting: the current sort and a callback; columns opt in with `sortKey`. */
  sort?: ServerSort | null;
  onSortChange?: (sort: ServerSort | null) => void;
  /** A background refetch is running (rows stay visible, slightly dimmed). */
  fetching?: boolean;
}) {
  const serverMode = !!onSortChange;
  const [clientSort, setClientSort] = useState<{ key: string; dir: 1 | -1 } | null>(null);

  const sorted = useMemo(() => {
    if (serverMode || !clientSort) return rows;
    const col = columns.find((c) => c.key === clientSort.key);
    if (!col?.sortValue) return rows;
    return [...rows].sort((a, b) => {
      const av = col.sortValue!(a);
      const bv = col.sortValue!(b);
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      if (typeof av === "number" && typeof bv === "number") return (av - bv) * clientSort.dir;
      return String(av).localeCompare(String(bv)) * clientSort.dir;
    });
  }, [rows, clientSort, columns, serverMode]);

  const sortable = (c: Column<T>) => (serverMode ? !!c.sortKey : !!c.sortValue);
  const activeDir = (c: Column<T>): "asc" | "desc" | null => {
    if (serverMode) return serverSort && c.sortKey === serverSort.key ? serverSort.dir : null;
    return clientSort?.key === c.key ? (clientSort.dir === 1 ? "asc" : "desc") : null;
  };
  const toggle = (c: Column<T>) => {
    if (!sortable(c)) return;
    if (serverMode) {
      const current = activeDir(c);
      onSortChange!(
        current === "asc" ? { key: c.sortKey!, dir: "desc" } : current === "desc" ? null : { key: c.sortKey!, dir: "asc" }
      );
    } else {
      setClientSort((s) => (s?.key === c.key ? { key: c.key, dir: s.dir === 1 ? -1 : 1 } : { key: c.key, dir: 1 }));
    }
  };

  if (loading) {
    return (
      <div className="glass-card overflow-hidden">
        <SkeletonRows rows={7} cols={columns.length} />
      </div>
    );
  }

  if (rows.length === 0 && (!pagination || pagination.page === 0)) {
    return (
      <div className="glass-card">
        <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />
      </div>
    );
  }

  const hideCls = (c: Column<T>) => (c.hideOnMobile ? "hidden sm:table-cell" : undefined);

  return (
    <div className="glass-card overflow-hidden">
      <div className={`overflow-x-auto thin-scroll transition-opacity ${fetching ? "opacity-60" : ""}`}>
        <table className="data-table">
          <thead>
            <tr>
              {columns.map((c) => {
                const dir = activeDir(c);
                return (
                  <th
                    key={c.key}
                    style={{ width: c.width, textAlign: c.align }}
                    className={[sortable(c) ? "cursor-pointer select-none" : "", hideCls(c) ?? ""].join(" ").trim() || undefined}
                    onClick={() => toggle(c)}
                    aria-sort={dir === "asc" ? "ascending" : dir === "desc" ? "descending" : undefined}
                  >
                    <span className="inline-flex items-center gap-1">
                      {c.header}
                      {sortable(c) &&
                        (dir === "asc" ? (
                          <ArrowUp size={11} />
                        ) : dir === "desc" ? (
                          <ArrowDown size={11} />
                        ) : (
                          <ArrowUpDown size={11} className="opacity-30" />
                        ))}
                    </span>
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {sorted.map((row) => (
              <tr key={row.id} onClick={() => onRowClick?.(row)}>
                {columns.map((c) => (
                  <td key={c.key} style={{ textAlign: c.align }} className={hideCls(c)}>
                    {c.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {pagination && <Pager {...pagination} shown={rows.length} />}
    </div>
  );
}

function Pager({ page, pageSize, total, onPageChange, shown }: Pagination & { shown: number }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : page * pageSize + 1;
  const to = page * pageSize + shown;
  return (
    <div
      className="flex items-center justify-between gap-3 px-4 py-2.5 border-t text-xs"
      style={{ borderColor: "rgb(var(--panel-border) / 0.08)", color: "var(--ink-muted)" }}
    >
      <span className="tabular-nums">
        {from.toLocaleString()}–{to.toLocaleString()} of {total.toLocaleString()}
      </span>
      <div className="flex items-center gap-1">
        <button
          className="btn-icon h-7 w-7"
          aria-label="Previous page"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
        >
          <ChevronLeft size={15} />
        </button>
        <span className="px-2 tabular-nums" style={{ color: "var(--ink-secondary)" }}>
          Page {page + 1} of {pages}
        </span>
        <button
          className="btn-icon h-7 w-7"
          aria-label="Next page"
          disabled={page + 1 >= pages}
          onClick={() => onPageChange(page + 1)}
        >
          <ChevronRight size={15} />
        </button>
      </div>
    </div>
  );
}
