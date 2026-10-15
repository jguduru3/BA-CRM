import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Plus, Settings2, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "@/lib/api";
import { iconFor } from "@/lib/iconPicker";
import { toast } from "@/lib/toast";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field } from "@/components/ui/Field";
import { Kanban, KanbanColumnDef } from "@/components/ui/Kanban";
import { Modal } from "@/components/ui/Modal";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import {
  cellValue,
  DbProperty,
  DbRecordEnvelope,
  parseOptions,
  PROPERTY_TYPES,
  PropertyType,
  toValuePayload,
} from "./propertyTypes";

const TYPE_LABEL: Record<PropertyType, string> = {
  text: "Text",
  number: "Number",
  date: "Date",
  select: "Select",
  multi_select: "Multi-select",
  checkbox: "Checkbox",
  url: "URL",
  email: "Email",
  phone: "Phone",
  file: "File (name/URL)",
  relation: "Relation (basic)",
  formula: "Formula (read-only)",
};

export default function DatabaseDetailPage() {
  const { id } = useParams();
  const dbId = Number(id);
  const qc = useQueryClient();
  const [view, setView] = useState<"table" | "board">("table");
  const [propsOpen, setPropsOpen] = useState(false);
  const [recordDrawer, setRecordDrawer] = useState<DbRecordEnvelope | null>(null);
  const [creatingRecord, setCreatingRecord] = useState(false);
  const [draftValues, setDraftValues] = useState<Record<number, unknown>>({});
  const [newProp, setNewProp] = useState<{ name: string; type: PropertyType; options: string }>({
    name: "",
    type: "text",
    options: "",
  });
  const { confirm, node: confirmNode } = useConfirm();

  const meta = useQuery({
    queryKey: ["database", dbId],
    queryFn: async () =>
      (await api.get<{ database: { id: number; name: string; icon: string | null; color: string | null }; properties: DbProperty[] }>(
        `/databases/${dbId}`
      )).data,
  });

  const records = useQuery({
    queryKey: ["database", dbId, "records"],
    queryFn: async () => (await api.get<DbRecordEnvelope[]>(`/databases/${dbId}/records`)).data,
  });

  const properties = (meta.data?.properties ?? []).sort((a, b) => a.position - b.position);
  const boardProp = properties.find((p) => p.type === "select");

  const addProperty = useMutation({
    mutationFn: async () => {
      const options =
        newProp.type === "select" || newProp.type === "multi_select"
          ? JSON.stringify(
              newProp.options
                .split(",")
                .map((s) => s.trim())
                .filter(Boolean)
                .map((label, i) => ({ id: String(i), label, color: "#2a78d6" }))
            )
          : null;
      return api.post(`/databases/${dbId}/properties`, { name: newProp.name, type: newProp.type, optionsJson: options, position: properties.length });
    },
    onSuccess: () => {
      toast.success("Property added");
      qc.invalidateQueries({ queryKey: ["database", dbId] });
      setNewProp({ name: "", type: "text", options: "" });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteProperty = useMutation({
    mutationFn: async (propId: number) => api.delete(`/databases/${dbId}/properties/${propId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["database", dbId] });
      qc.invalidateQueries({ queryKey: ["database", dbId, "records"] });
    },
  });

  const openEdit = (env: DbRecordEnvelope) => {
    const vals: Record<number, unknown> = {};
    properties.forEach((p) => (vals[p.id] = cellValue(env, p)));
    setDraftValues(vals);
    setRecordDrawer(env);
  };

  const openCreate = () => {
    const vals: Record<number, unknown> = {};
    properties.forEach((p) => (vals[p.id] = p.type === "checkbox" ? false : p.type === "multi_select" ? [] : ""));
    setDraftValues(vals);
    setCreatingRecord(true);
  };

  const closeDrawers = () => {
    setRecordDrawer(null);
    setCreatingRecord(false);
  };

  const saveRecord = useMutation({
    mutationFn: async () => {
      const values = properties.filter((p) => p.type !== "formula").map((p) => toValuePayload(p.id, p.type, draftValues[p.id]));
      if (recordDrawer) return api.put(`/databases/${dbId}/records/${recordDrawer.record.id}`, { values });
      return api.post(`/databases/${dbId}/records`, { values });
    },
    onSuccess: () => {
      toast.success(recordDrawer ? "Record updated" : "Record created");
      qc.invalidateQueries({ queryKey: ["database", dbId, "records"] });
      closeDrawers();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteRecord = useMutation({
    mutationFn: async (recId: number) => api.delete(`/databases/${dbId}/records/${recId}`),
    onSuccess: () => {
      toast.success("Record deleted");
      qc.invalidateQueries({ queryKey: ["database", dbId, "records"] });
      closeDrawers();
    },
  });

  // ── Board view grouping ──────────────────────────────────────────────────
  const boardOptions = boardProp ? parseOptions(boardProp.optionsJson) : [];
  const boardColumns: KanbanColumnDef[] = boardOptions.map((o) => ({ key: o.label, label: o.label, accent: o.color }));
  type BoardCard = DbRecordEnvelope & { id: number };
  const boardItems: Record<string, BoardCard[]> = useMemo(() => {
    if (!boardProp) return {};
    const map: Record<string, BoardCard[]> = {};
    boardOptions.forEach((o) => (map[o.label] = []));
    (records.data ?? []).forEach((env) => {
      const v = (cellValue(env, boardProp) as string) || "Unassigned";
      (map[v] ??= []).push({ ...env, id: env.record.id });
    });
    return map;
  }, [records.data, boardProp]); // eslint-disable-line react-hooks/exhaustive-deps

  async function moveCard(recId: number, _from: string, to: string) {
    if (!boardProp) return;
    try {
      await api.put(`/databases/${dbId}/records/${recId}`, { values: [{ propertyId: boardProp.id, textValue: to }] });
      qc.invalidateQueries({ queryKey: ["database", dbId, "records"] });
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not move record");
    }
  }

  const titleProp = properties[0];
  const recordTitle = (env: DbRecordEnvelope) => (titleProp ? String(cellValue(env, titleProp) ?? `Record #${env.record.id}`) : `#${env.record.id}`);

  if (meta.isLoading) return <Skeleton className="h-[60vh]" />;
  const Icon = iconFor(meta.data?.database.icon);

  return (
    <div className="flex flex-col gap-5 flex-1 min-h-0">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-3 min-w-0">
          <Link to="/databases" className="btn-icon shrink-0">
            <ArrowLeft size={17} />
          </Link>
          <div
            className="h-10 w-10 rounded-xl flex items-center justify-center shrink-0"
            style={{ backgroundColor: `${meta.data?.database.color || "#1ca3e6"}22`, color: meta.data?.database.color || "var(--brand-600)" }}
          >
            <Icon size={18} />
          </div>
          <h1 className="text-lg font-bold truncate" style={{ color: "var(--ink-primary)" }}>
            {meta.data?.database.name}
          </h1>
        </div>
        <div className="flex items-center gap-2">
          <button className="btn-secondary" onClick={() => setPropsOpen(true)}>
            <Settings2 size={15} /> Properties
          </button>
          <button className="btn-primary" onClick={openCreate} disabled={properties.length === 0}>
            <Plus size={15} /> New record
          </button>
        </div>
      </div>

      {properties.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={Settings2}
            title="Add your first property"
            description="Properties define the columns of this database — text, number, select, checkbox and more."
            action={
              <button className="btn-primary" onClick={() => setPropsOpen(true)}>
                <Plus size={15} /> Add property
              </button>
            }
          />
        </div>
      ) : (
        <>
          <Tabs
            value={view}
            onChange={setView}
            tabs={[
              { value: "table", label: "Table" },
              ...(boardProp ? [{ value: "board" as const, label: `Board · ${boardProp.name}` }] : []),
            ]}
          />

          {view === "table" ? (
            <div className="glass-card overflow-hidden">
              <div className="overflow-x-auto thin-scroll">
                <table className="data-table">
                  <thead>
                    <tr>
                      {properties.map((p) => (
                        <th key={p.id}>{p.name}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {(records.data ?? []).length === 0 ? (
                      <tr>
                        <td colSpan={properties.length} className="text-center py-10 text-sm" style={{ color: "var(--ink-muted)" }}>
                          No records yet — click "New record" to add one.
                        </td>
                      </tr>
                    ) : (
                      records.data!.map((env) => (
                        <tr key={env.record.id} onClick={() => openEdit(env)}>
                          {properties.map((p) => (
                            <td key={p.id} className="text-sm">
                              <CellDisplay prop={p} value={cellValue(env, p)} />
                            </td>
                          ))}
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          ) : (
            <div className="flex-1 min-h-[480px]">
              <Kanban
                columns={boardColumns}
                itemsByColumn={boardItems}
                loading={records.isLoading}
                onMove={(recId, from, to) => moveCard(recId, from, to)}
                renderCard={(env) => (
                  <div onClick={() => openEdit(env)}>
                    <p className="text-sm font-medium" style={{ color: "var(--ink-primary)" }}>
                      {recordTitle(env)}
                    </p>
                  </div>
                )}
              />
            </div>
          )}
        </>
      )}

      {/* Record drawer */}
      <Drawer
        open={!!recordDrawer || creatingRecord}
        onClose={closeDrawers}
        title={recordDrawer ? recordTitle(recordDrawer) : "New record"}
        headerExtra={
          recordDrawer && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              onClick={async () => {
                const ok = await confirm("Delete this record?", "This cannot be undone.");
                if (ok) deleteRecord.mutate(recordDrawer.record.id);
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <div className="space-y-4">
          {properties.map((p) => (
            <Field key={p.id} label={p.name}>
              <PropertyInput prop={p} value={draftValues[p.id]} onChange={(v) => setDraftValues((d) => ({ ...d, [p.id]: v }))} />
            </Field>
          ))}
        </div>
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          <button className="btn-primary" onClick={() => saveRecord.mutate()}>
            {recordDrawer ? "Save changes" : "Create record"}
          </button>
        </div>
        {recordDrawer && (
          <div className="mt-6 pt-4 hairline border-t">
            <AttachmentList relatedType="DatabaseRecord" relatedId={recordDrawer.record.id} />
          </div>
        )}
      </Drawer>

      {/* Properties management */}
      <Modal open={propsOpen} onClose={() => setPropsOpen(false)} title="Properties" width="28rem">
        <div className="space-y-4">
          <div className="space-y-1.5 max-h-56 overflow-y-auto thin-scroll">
            {properties.map((p) => (
              <div key={p.id} className="flex items-center justify-between py-1.5 px-2 rounded-lg hover:bg-[rgb(var(--panel-border)/0.05)]">
                <div>
                  <p className="text-sm font-medium">{p.name}</p>
                  <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                    {TYPE_LABEL[p.type]}
                  </p>
                </div>
                <button className="btn-icon h-7 w-7" style={{ color: "var(--critical)" }} onClick={() => deleteProperty.mutate(p.id)}>
                  <Trash2 size={13} />
                </button>
              </div>
            ))}
          </div>
          <div className="pt-3 hairline border-t space-y-3">
            <p className="field-label">Add property</p>
            <input className="input" placeholder="Property name" value={newProp.name} onChange={(e) => setNewProp((d) => ({ ...d, name: e.target.value }))} />
            <select className="select" value={newProp.type} onChange={(e) => setNewProp((d) => ({ ...d, type: e.target.value as PropertyType }))}>
              {PROPERTY_TYPES.map((t) => (
                <option key={t} value={t}>
                  {TYPE_LABEL[t]}
                </option>
              ))}
            </select>
            {(newProp.type === "select" || newProp.type === "multi_select") && (
              <input
                className="input"
                placeholder="Options, comma-separated"
                value={newProp.options}
                onChange={(e) => setNewProp((d) => ({ ...d, options: e.target.value }))}
              />
            )}
            <button className="btn-primary w-full" disabled={!newProp.name.trim()} onClick={() => addProperty.mutate()}>
              <Plus size={14} /> Add property
            </button>
          </div>
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}

function CellDisplay({ prop, value }: { prop: DbProperty; value: unknown }) {
  if (value == null || value === "") return <span style={{ color: "var(--ink-muted)" }}>—</span>;
  if (prop.type === "checkbox") return <input type="checkbox" checked={!!value} disabled className="checkbox" />;
  if (prop.type === "multi_select" && Array.isArray(value))
    return (
      <div className="flex flex-wrap gap-1">
        {value.map((v, i) => (
          <span key={i} className="badge-brand">
            {v}
          </span>
        ))}
      </div>
    );
  if (prop.type === "select") return <span className="badge-neutral">{String(value)}</span>;
  if (prop.type === "url")
    return (
      <a href={String(value)} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()} style={{ color: "var(--brand-600)" }}>
        {String(value)}
      </a>
    );
  return <span>{String(value)}</span>;
}

function PropertyInput({ prop, value, onChange }: { prop: DbProperty; value: unknown; onChange: (v: unknown) => void }) {
  if (prop.type === "checkbox") {
    return <input type="checkbox" className="checkbox" checked={!!value} onChange={(e) => onChange(e.target.checked)} />;
  }
  if (prop.type === "select") {
    const options = parseOptions(prop.optionsJson);
    return (
      <select className="select" value={(value as string) ?? ""} onChange={(e) => onChange(e.target.value)}>
        <option value="">—</option>
        {options.map((o) => (
          <option key={o.id} value={o.label}>
            {o.label}
          </option>
        ))}
      </select>
    );
  }
  if (prop.type === "multi_select") {
    const options = parseOptions(prop.optionsJson);
    const selected = Array.isArray(value) ? (value as string[]) : [];
    return (
      <div className="flex flex-wrap gap-1.5">
        {options.map((o) => {
          const active = selected.includes(o.label);
          return (
            <button
              key={o.id}
              type="button"
              className={active ? "badge-brand" : "badge-neutral"}
              onClick={() => onChange(active ? selected.filter((s) => s !== o.label) : [...selected, o.label])}
            >
              {o.label}
            </button>
          );
        })}
      </div>
    );
  }
  if (prop.type === "number") {
    return <input className="input" type="number" value={(value as number) ?? ""} onChange={(e) => onChange(e.target.value)} />;
  }
  if (prop.type === "date") {
    return <input className="input" type="date" value={(value as string) ?? ""} onChange={(e) => onChange(e.target.value)} />;
  }
  if (prop.type === "formula") {
    return (
      <input className="input" value={(value as string) ?? ""} disabled placeholder="Computed (not yet evaluated)" />
    );
  }
  return (
    <input
      className="input"
      type={prop.type === "email" ? "email" : prop.type === "url" ? "url" : "text"}
      value={(value as string) ?? ""}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}
