import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Check, FileText, Link2, Search, Upload } from "lucide-react";
import { useMemo, useRef, useState } from "react";
import { api, downloadFile } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { AttachmentRow } from "@/components/ui/AttachmentList";
import { SafeLink } from "@/components/ui/SafeLink";
import {
  cellValue,
  type DbProperty,
  type DbRecordEnvelope,
  type FormulaCell,
  parseOptions,
  recordTitleOf,
} from "./propertyTypes";

const muted = { color: "var(--ink-muted)" };

function OptionBadge({ label, color }: { label: string; color?: string }) {
  const c = color || "#64748b";
  return (
    <span className="badge" style={{ backgroundColor: `${c}1f`, color: c }}>
      {label}
    </span>
  );
}

/** Read-only rendering of one typed cell. User text is always plain text; links only via SafeLink. */
export function CellDisplay({ prop, env }: { prop: DbProperty; env: DbRecordEnvelope }) {
  const value = cellValue(env, prop);
  switch (prop.type) {
    case "checkbox":
      return value ? (
        <span className="inline-flex h-5 w-5 items-center justify-center rounded-md text-white" style={{ backgroundImage: "var(--brand-gradient)" }}>
          <Check size={13} />
        </span>
      ) : (
        <span className="inline-block h-5 w-5 rounded-md border" style={{ borderColor: "rgb(var(--panel-border) / 0.2)" }} />
      );
    case "select": {
      if (!value) return <span style={muted}>—</span>;
      const opt = parseOptions(prop.optionsJson).find((o) => o.id === value || o.label === value);
      return <OptionBadge label={opt?.label ?? String(value)} color={opt?.color} />;
    }
    case "multi_select": {
      const vals = value as string[];
      if (!vals.length) return <span style={muted}>—</span>;
      const opts = parseOptions(prop.optionsJson);
      return (
        <div className="flex flex-wrap gap-1">
          {vals.map((v) => {
            const o = opts.find((x) => x.id === v || x.label === v);
            return <OptionBadge key={v} label={o?.label ?? v} color={o?.color} />;
          })}
        </div>
      );
    }
    case "relation": {
      const ids = value as number[];
      const titles = env.relations ?? {};
      const live = ids.filter((id) => titles[String(id)] != null);
      if (!live.length) return <span style={muted}>—</span>;
      return (
        <div className="flex flex-wrap gap-1">
          {live.map((id) => (
            <span key={id} className="badge-brand inline-flex items-center gap-1 max-w-[12rem]">
              <Link2 size={10} className="shrink-0" />
              <span className="truncate">{titles[String(id)]}</span>
            </span>
          ))}
        </div>
      );
    }
    case "file": {
      const ids = value as number[];
      return ids.length ? (
        <span className="badge-neutral inline-flex items-center gap-1">
          <FileText size={10} /> {ids.length} file{ids.length === 1 ? "" : "s"}
        </span>
      ) : (
        <span style={muted}>—</span>
      );
    }
    case "formula": {
      const cell = value as FormulaCell | null;
      if (!cell) return <span style={muted}>—</span>;
      if (cell.error)
        return (
          <span className="badge-critical inline-flex items-center gap-1" title="Formula error">
            <AlertTriangle size={10} /> {cell.error}
          </span>
        );
      if (cell.value == null || cell.value === "") return <span style={muted}>—</span>;
      if (typeof cell.value === "boolean") return <span>{cell.value ? "✓ true" : "false"}</span>;
      return <span className="font-medium tabular-nums">{typeof cell.value === "number" ? cell.value.toLocaleString() : String(cell.value)}</span>;
    }
    case "url":
      return value ? (
        <span onClick={(e) => e.stopPropagation()} className="truncate inline-block max-w-[14rem] align-bottom">
          <SafeLink href={String(value)} />
        </span>
      ) : (
        <span style={muted}>—</span>
      );
    case "email":
      return value ? (
        <span onClick={(e) => e.stopPropagation()}>
          <SafeLink href={`mailto:${String(value)}`}>{String(value)}</SafeLink>
        </span>
      ) : (
        <span style={muted}>—</span>
      );
    case "number":
      return value == null ? <span style={muted}>—</span> : <span className="tabular-nums">{Number(value).toLocaleString()}</span>;
    case "date":
      return value ? <span>{formatDate(String(value))}</span> : <span style={muted}>—</span>;
    default:
      return value ? <span className="line-clamp-2">{String(value)}</span> : <span style={muted}>—</span>;
  }
}

/** Editing control for one property; `recordId` enables file uploads (null while creating). */
export function PropertyInput({
  prop,
  value,
  onChange,
  recordId,
  env,
  disabled,
}: {
  prop: DbProperty;
  value: unknown;
  onChange: (v: unknown) => void;
  recordId: number | null;
  env: DbRecordEnvelope | null;
  disabled?: boolean;
}) {
  switch (prop.type) {
    case "checkbox":
      return <input type="checkbox" className="checkbox" checked={!!value} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />;
    case "select": {
      const options = parseOptions(prop.optionsJson);
      return (
        <select className="select" value={(value as string) ?? ""} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
          <option value="">—</option>
          {options.map((o) => (
            <option key={o.id} value={o.label}>
              {o.label}
            </option>
          ))}
        </select>
      );
    }
    case "multi_select": {
      const options = parseOptions(prop.optionsJson);
      const selected = Array.isArray(value) ? (value as string[]) : [];
      return (
        <div className="flex flex-wrap gap-1.5">
          {options.map((o) => {
            const active = selected.includes(o.label) || selected.includes(o.id);
            return (
              <button
                key={o.id}
                type="button"
                disabled={disabled}
                className="badge transition-all"
                style={{
                  backgroundColor: active ? o.color : `${o.color}14`,
                  color: active ? "white" : o.color,
                }}
                onClick={() => onChange(active ? selected.filter((s) => s !== o.label && s !== o.id) : [...selected, o.label])}
              >
                {o.label}
              </button>
            );
          })}
          {options.length === 0 && <span className="text-xs" style={muted}>No options defined.</span>}
        </div>
      );
    }
    case "number":
      return <input className="input" type="number" value={(value as number) ?? ""} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
    case "date":
      return <input className="input" type="date" value={(value as string) ?? ""} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
    case "formula": {
      const cell = env ? (cellValue(env, prop) as FormulaCell | null) : null;
      return (
        <div className="input flex items-center gap-2 cursor-default" style={{ backgroundColor: "rgb(var(--panel-border) / 0.03)" }}>
          {env ? <CellDisplay prop={prop} env={env} /> : <span style={muted}>Computed after saving</span>}
          {cell?.error && <span className="text-[11px]" style={muted}>check the formula</span>}
        </div>
      );
    }
    case "relation":
      return <RelationPicker prop={prop} value={Array.isArray(value) ? (value as number[]) : []} onChange={onChange} disabled={disabled} />;
    case "file":
      return <FilePicker recordId={recordId} value={Array.isArray(value) ? (value as number[]) : []} onChange={onChange} disabled={disabled} />;
    case "phone":
      return <input className="input" type="tel" inputMode="tel" value={(value as string) ?? ""} disabled={disabled} onChange={(e) => onChange(e.target.value)} />;
    default:
      return (
        <input
          className="input"
          type={prop.type === "email" ? "email" : prop.type === "url" ? "url" : "text"}
          placeholder={prop.type === "url" ? "https://…" : undefined}
          value={(value as string) ?? ""}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
        />
      );
  }
}

/** Searchable checklist of the related database's records, stored as record ids. */
function RelationPicker({
  prop,
  value,
  onChange,
  disabled,
}: {
  prop: DbProperty;
  value: number[];
  onChange: (v: unknown) => void;
  disabled?: boolean;
}) {
  const [q, setQ] = useState("");
  const relId = prop.relationDbId;
  const meta = useQuery({
    queryKey: ["database", relId],
    queryFn: async () => (await api.get<{ properties: DbProperty[] }>(`/databases/${relId}`)).data,
    enabled: !!relId,
  });
  const records = useQuery({
    queryKey: ["database", relId, "records", "picker"],
    queryFn: async () => (await api.get<DbRecordEnvelope[]>(`/databases/${relId}/records`, { params: { size: 500 } })).data,
    enabled: !!relId,
  });
  const options = useMemo(() => {
    const props = meta.data?.properties ?? [];
    return (records.data ?? []).map((env) => ({ id: env.record.id, title: recordTitleOf(env, props) }));
  }, [meta.data, records.data]);
  const filtered = options.filter((o) => !q || o.title.toLowerCase().includes(q.toLowerCase()));

  if (!relId) return <p className="text-xs" style={muted}>The related database was deleted.</p>;
  if (records.isError) return <p className="text-xs" style={{ color: "var(--critical)" }}>Couldn't load the related records.</p>;
  return (
    <div className="rounded-xl border p-2" style={{ borderColor: "rgb(var(--panel-border) / 0.12)" }}>
      <label className="relative block mb-1.5">
        <Search size={13} className="absolute left-2 top-1/2 -translate-y-1/2" style={muted} />
        <input className="input pl-7 py-1.5 text-sm" placeholder="Find a record…" value={q} onChange={(e) => setQ(e.target.value)} />
      </label>
      <div className="max-h-44 overflow-y-auto thin-scroll space-y-0.5">
        {records.isLoading ? (
          <p className="text-xs p-2" style={muted}>
            Loading…
          </p>
        ) : filtered.length === 0 ? (
          <p className="text-xs p-2" style={muted}>
            No records.
          </p>
        ) : (
          filtered.map((o) => {
            const checked = value.includes(o.id);
            return (
              <label key={o.id} className="flex items-center gap-2 px-2 py-1 rounded-lg text-sm cursor-pointer hover:bg-[rgb(var(--panel-border)/0.05)]">
                <input
                  type="checkbox"
                  className="checkbox"
                  disabled={disabled}
                  checked={checked}
                  onChange={() => onChange(checked ? value.filter((x) => x !== o.id) : [...value, o.id])}
                />
                <span className="truncate">{o.title}</span>
              </label>
            );
          })
        )}
      </div>
    </div>
  );
}

/** Files of the record (uploaded as its attachments); the property stores the chosen attachment ids. */
function FilePicker({
  recordId,
  value,
  onChange,
  disabled,
}: {
  recordId: number | null;
  value: number[];
  onChange: (v: unknown) => void;
  disabled?: boolean;
}) {
  const qc = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const key = ["attachments", "DatabaseRecord", recordId];
  const files = useQuery({
    queryKey: key,
    queryFn: async () =>
      (await api.get<AttachmentRow[]>("/attachments", { params: { relatedType: "DatabaseRecord", relatedId: recordId } })).data,
    enabled: recordId != null,
  });
  const upload = useMutation({
    mutationFn: async (file: File) => {
      const form = new FormData();
      form.append("file", file);
      form.append("relatedType", "DatabaseRecord");
      form.append("relatedId", String(recordId));
      return (await api.post<AttachmentRow>("/attachments", form, { headers: { "Content-Type": "multipart/form-data" } })).data;
    },
    onSuccess: (a) => {
      qc.invalidateQueries({ queryKey: key });
      onChange([...value, a.id]);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (recordId == null) return <p className="text-xs" style={muted}>Save the record first, then attach files.</p>;
  return (
    <div className="space-y-1.5">
      {(files.data ?? []).map((f) => {
        const checked = value.includes(f.id);
        return (
          <div key={f.id} className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="checkbox"
              disabled={disabled}
              checked={checked}
              aria-label={`Use ${f.fileName}`}
              onChange={() => onChange(checked ? value.filter((x) => x !== f.id) : [...value, f.id])}
            />
            <button
              type="button"
              className="truncate hover:underline text-left"
              onClick={() => downloadFile(`/attachments/${f.id}/download`, f.fileName).catch((e: Error) => toast.error(e.message))}
            >
              {f.fileName}
            </button>
          </div>
        );
      })}
      {!disabled && (
        <>
          <button type="button" className="btn-secondary px-2.5 py-1 text-xs" disabled={upload.isPending} onClick={() => inputRef.current?.click()}>
            <Upload size={12} /> {upload.isPending ? "Uploading…" : "Upload file"}
          </button>
          <input ref={inputRef} type="file" hidden onChange={(e) => e.target.files?.[0] && upload.mutate(e.target.files[0])} />
        </>
      )}
    </div>
  );
}
