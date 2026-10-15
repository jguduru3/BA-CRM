import { useMutation } from "@tanstack/react-query";
import { Eye, Filter, Plus, UserPlus, X } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { toast } from "@/lib/toast";
import type { AddRecipientsResult, AudienceFilter, AudiencePreview } from "./campaignTypes";

const FIELDS: { value: AudienceFilter["field"]; label: string; numeric?: boolean }[] = [
  { value: "stage", label: "Stage" },
  { value: "industry", label: "Industry" },
  { value: "geography", label: "Geography" },
  { value: "source", label: "Source" },
  { value: "priority", label: "Priority" },
  { value: "value", label: "Deal value", numeric: true },
];
const TEXT_OPS: { value: AudienceFilter["op"]; label: string }[] = [
  { value: "eq", label: "is" },
  { value: "neq", label: "is not" },
  { value: "in", label: "is any of" },
];
const NUM_OPS: { value: AudienceFilter["op"]; label: string }[] = [
  { value: "gte", label: "≥" },
  { value: "lte", label: "≤" },
  { value: "gt", label: ">" },
  { value: "lt", label: "<" },
  { value: "eq", label: "=" },
];

export function parseCriteria(json: string | null | undefined): AudienceFilter[] {
  if (!json) return [];
  try {
    const v = JSON.parse(json);
    return Array.isArray(v?.filters) ? (v.filters as AudienceFilter[]) : [];
  } catch {
    return [];
  }
}

export function buildCriteria(filters: AudienceFilter[]): string {
  const clean = filters
    .filter((f) => (f.op === "in" ? (f.values ?? []).length > 0 : f.value !== undefined && String(f.value).trim() !== ""))
    .map((f) =>
      f.op === "in"
        ? { field: f.field, op: f.op, values: f.values }
        : { field: f.field, op: f.op, value: f.field === "value" ? Number(f.value) : String(f.value).trim() }
    );
  return JSON.stringify({ entity: "lead", filters: clean });
}

export function AudienceBuilder({
  campaignId,
  initialCriteria,
  editable,
  onApplied,
}: {
  campaignId: number;
  initialCriteria?: string | null;
  editable: boolean;
  onApplied: (r: AddRecipientsResult) => void;
}) {
  const [filters, setFilters] = useState<AudienceFilter[]>(() => parseCriteria(initialCriteria));
  const [preview, setPreview] = useState<AudiencePreview | null>(null);
  const criteria = buildCriteria(filters);

  const runPreview = useMutation({
    mutationFn: async () =>
      (await api.post<AudiencePreview>(`/campaigns/${campaignId}/audience/preview`, { criteriaJson: criteria })).data,
    onSuccess: setPreview,
    onError: (e: Error) => toast.error(e.message),
  });

  const apply = useMutation({
    mutationFn: async () =>
      (await api.post<AddRecipientsResult>(`/campaigns/${campaignId}/audience/apply`, { criteriaJson: criteria })).data,
    onSuccess: (r) => {
      onApplied(r);
      setPreview(null);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const update = (i: number, patch: Partial<AudienceFilter>) =>
    setFilters((fs) => fs.map((f, idx) => (idx === i ? { ...f, ...patch } : f)));

  return (
    <div className="glass-solid rounded-xl p-4 space-y-3">
      <div className="flex items-center gap-2">
        <Filter size={14} style={{ color: "var(--brand-600)" }} />
        <span className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
          Audience from leads
        </span>
      </div>

      {filters.length === 0 && (
        <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
          No filters — every lead with a phone number matches. Add filters to narrow it down.
        </p>
      )}
      <div className="space-y-2">
        {filters.map((f, i) => {
          const numeric = f.field === "value";
          return (
            <div key={i} className="flex flex-wrap items-center gap-2">
              <select
                className="select !w-auto min-w-[8rem] flex-1 sm:flex-none"
                value={f.field}
                disabled={!editable}
                onChange={(e) => {
                  const field = e.target.value as AudienceFilter["field"];
                  update(i, { field, op: field === "value" ? "gte" : "eq", value: "", values: [] });
                }}
              >
                {FIELDS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <select
                className="select !w-auto min-w-[6rem]"
                value={f.op}
                disabled={!editable}
                onChange={(e) => update(i, { op: e.target.value as AudienceFilter["op"] })}
              >
                {(numeric ? NUM_OPS : TEXT_OPS).map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <input
                className="input flex-1 min-w-[8rem]"
                type={numeric ? "number" : "text"}
                disabled={!editable}
                placeholder={f.op === "in" ? "Comma-separated values" : numeric ? "100000" : "Value"}
                value={f.op === "in" ? (f.values ?? []).join(", ") : (f.value ?? "")}
                onChange={(e) =>
                  f.op === "in"
                    ? update(i, { values: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) })
                    : update(i, { value: e.target.value })
                }
              />
              {editable && (
                <button className="btn-icon h-8 w-8" aria-label="Remove filter" onClick={() => setFilters((fs) => fs.filter((_, idx) => idx !== i))}>
                  <X size={14} />
                </button>
              )}
            </div>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center gap-2 pt-1">
        {editable && (
          <button className="btn-ghost text-xs" onClick={() => setFilters((fs) => [...fs, { field: "stage", op: "eq", value: "" }])}>
            <Plus size={13} /> Add filter
          </button>
        )}
        <div className="flex-1" />
        <button className="btn-secondary" disabled={runPreview.isPending} onClick={() => runPreview.mutate()}>
          <Eye size={14} /> {runPreview.isPending ? "Counting…" : "Preview audience"}
        </button>
        {editable && (
          <button className="btn-primary" disabled={apply.isPending || !preview || preview.count === 0} onClick={() => apply.mutate()}>
            <UserPlus size={14} /> {apply.isPending ? "Adding…" : `Add ${preview ? preview.count.toLocaleString() : ""} recipients`}
          </button>
        )}
      </div>

      {preview && (
        <div className="space-y-2 pt-2 hairline border-t">
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs" style={{ color: "var(--ink-secondary)" }}>
            <span>
              <strong className="tabular-nums" style={{ color: "var(--ink-primary)" }}>
                {preview.count.toLocaleString()}
              </strong>{" "}
              leads with a phone number
            </span>
            {preview.withoutPhone > 0 && <span>{preview.withoutPhone.toLocaleString()} more match but have no phone</span>}
          </div>
          {preview.recipients.length > 0 && (
            <div className="max-h-56 overflow-y-auto thin-scroll rounded-lg hairline border">
              <table className="w-full text-xs">
                <tbody>
                  {preview.recipients.map((r) => (
                    <tr key={r.relatedId} className="hairline border-b last:border-b-0">
                      <td className="px-3 py-1.5 truncate max-w-[10rem]" style={{ color: "var(--ink-primary)" }}>
                        {r.name ?? "—"}
                      </td>
                      <td className="px-3 py-1.5 font-mono tabular-nums" style={{ color: "var(--ink-secondary)" }}>
                        {r.normalizedPhone ?? r.phone}
                      </td>
                      <td className="px-3 py-1.5 text-right">
                        {!r.validPhone ? (
                          <span className="badge-warning">invalid number</span>
                        ) : r.optedOut ? (
                          <span className="badge-critical">opted out</span>
                        ) : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {preview.truncated && (
            <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
              Showing the first {preview.recipients.length} of {preview.count.toLocaleString()}.
            </p>
          )}
        </div>
      )}
    </div>
  );
}
