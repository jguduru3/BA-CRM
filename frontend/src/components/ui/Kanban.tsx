import { useState } from "react";
import { SkeletonCards } from "./Skeleton";

export interface KanbanColumnDef {
  key: string;
  label: string;
  accent?: string;
  footer?: React.ReactNode;
}

/**
 * Generic drag-and-drop board (native HTML5 DnD — no extra dependency).
 * Used for the Lead/Opportunity pipelines, Task board, and the Custom
 * Database board view — each page supplies its own card renderer.
 */
export function Kanban<T extends { id: number }>({
  columns,
  itemsByColumn,
  renderCard,
  onMove,
  loading,
  cardClassName = "",
  draggable = true,
}: {
  columns: KanbanColumnDef[];
  itemsByColumn: Record<string, T[]>;
  renderCard: (item: T) => React.ReactNode;
  onMove: (id: number, fromKey: string, toKey: string) => void;
  loading?: boolean;
  cardClassName?: string;
  draggable?: boolean;
}) {
  const [dragging, setDragging] = useState<{ id: number; from: string } | null>(null);
  const [overCol, setOverCol] = useState<string | null>(null);

  if (loading) return <SkeletonCards count={8} />;

  return (
    <div className="kanban-track h-full">
      {columns.map((col) => {
        const items = itemsByColumn[col.key] ?? [];
        const total = items.reduce((s, i) => { const v = (i as { value?: unknown }).value; return s + (typeof v === "number" ? v : 0); }, 0);
        return (
          <div
            key={col.key}
            className={`kanban-column ${overCol === col.key ? "is-over" : ""}`}
            onDragOver={(e) => {
              e.preventDefault();
              setOverCol(col.key);
            }}
            onDragLeave={() => setOverCol((c) => (c === col.key ? null : c))}
            onDrop={(e) => {
              e.preventDefault();
              setOverCol(null);
              if (dragging && dragging.from !== col.key) {
                onMove(dragging.id, dragging.from, col.key);
              }
              setDragging(null);
            }}
          >
            <div className="flex items-center justify-between px-3.5 pt-3.5 pb-2.5 shrink-0">
              <div className="flex items-center gap-2 min-w-0">
                <span
                  className="h-2 w-2 rounded-full shrink-0"
                  style={{ backgroundColor: col.accent || "var(--brand-500)" }}
                />
                <span className="text-[13px] font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                  {col.label}
                </span>
                <span
                  className="text-[11px] font-bold px-1.5 rounded-full shrink-0"
                  style={{ backgroundColor: "rgb(var(--panel-border) / 0.08)", color: "var(--ink-muted)" }}
                >
                  {items.length}
                </span>
              </div>
            </div>
            {total > 0 && (
              <div className="px-3.5 pb-2 text-[11px] font-semibold tabular-nums" style={{ color: "var(--ink-muted)" }}>
                {new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", notation: "compact" }).format(total)}
              </div>
            )}
            <div className="flex-1 overflow-y-auto thin-scroll px-3 pb-3 space-y-2.5 min-h-[80px]">
              {items.map((item) => (
                <div
                  key={item.id}
                  draggable={draggable}
                  onDragStart={() => draggable && setDragging({ id: item.id, from: col.key })}
                  onDragEnd={() => setDragging(null)}
                  className={`kanban-card ${draggable ? "" : "cursor-pointer active:cursor-pointer"} ${cardClassName}`}
                >
                  {renderCard(item)}
                </div>
              ))}
              {items.length === 0 && (
                <div
                  className="rounded-xl border border-dashed text-center text-xs py-4"
                  style={{ borderColor: "rgb(var(--panel-border) / 0.15)", color: "var(--ink-muted)" }}
                >
                  Drop here
                </div>
              )}
            </div>
            {col.footer}
          </div>
        );
      })}
    </div>
  );
}
