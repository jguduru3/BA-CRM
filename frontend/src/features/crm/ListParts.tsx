import { Download, Loader2 } from "lucide-react";
import { useState } from "react";
import { ApiError, downloadFile } from "@/lib/api";
import { toast } from "@/lib/toast";

/**
 * "Export" button for a list page: streams /api/exports/{type} as CSV. The file
 * holds exactly the rows (and masked fields) the user can see on screen.
 */
export function ExportButton({ type, label = "Export" }: { type: string; label?: string }) {
  const [busy, setBusy] = useState(false);
  return (
    <button
      className="btn-secondary"
      disabled={busy}
      title={`Download ${type} as CSV`}
      onClick={async () => {
        setBusy(true);
        try {
          await downloadFile(`/exports/${type}`, `${type}.csv`);
        } catch (e) {
          toast.error(e instanceof Error ? e.message : "Export failed");
        } finally {
          setBusy(false);
        }
      }}
    >
      {busy ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
      <span className="hidden sm:inline">{label}</span>
    </button>
  );
}

/** Toolbar row under a page header: tabs/filters on the left, search + actions on the right. */
export function ListToolbar({ left, right }: { left?: React.ReactNode; right?: React.ReactNode }) {
  return (
    <div className="flex flex-col sm:flex-row sm:flex-wrap sm:items-center justify-between gap-3">
      <div className="flex flex-wrap items-center gap-2 min-w-0">{left}</div>
      <div className="flex flex-wrap items-center gap-2 w-full sm:w-auto">{right}</div>
    </div>
  );
}

/** Small labelled select used as a list filter. */
export function FilterSelect({
  value,
  onChange,
  options,
  allLabel,
  ariaLabel,
}: {
  value: string;
  onChange: (v: string) => void;
  options: (string | { value: string; label: string })[];
  allLabel: string;
  ariaLabel: string;
}) {
  return (
    <select className="select w-auto min-w-[8.5rem]" aria-label={ariaLabel} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{allLabel}</option>
      {options.map((o) =>
        typeof o === "string" ? (
          <option key={o} value={o}>
            {o}
          </option>
        ) : (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        )
      )}
    </select>
  );
}

/** Human message for a failed save — calls out concurrent edits explicitly. */
export function saveErrorMessage(e: unknown, what = "record"): string {
  if (e instanceof ApiError && e.code === "STALE_UPDATE") {
    return `This ${what} was changed by someone else while you were editing. Close it, reopen and try again.`;
  }
  return e instanceof Error ? e.message : "Could not save";
}

/**
 * The fields of `draft` that differ from `original` — what a PATCH should send,
 * so an edit never re-sends (and overwrites) values the user didn't touch.
 */
export function changedFields<T extends object>(original: T, draft: Partial<T>): Partial<T> {
  const out: Record<string, unknown> = {};
  const base = original as Record<string, unknown>;
  for (const [k, v] of Object.entries(draft)) {
    if (v !== base[k]) out[k] = v;
  }
  return out as Partial<T>;
}

/** "3d", "5h", "just now" — compact age for Kanban cards. */
export function ageLabel(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return "";
  const ms = now - new Date(iso).getTime();
  if (!Number.isFinite(ms) || ms < 0) return "";
  const mins = Math.floor(ms / 60_000);
  if (mins < 60) return mins <= 1 ? "just now" : `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  if (days < 60) return `${days}d`;
  return `${Math.floor(days / 30)}mo`;
}
