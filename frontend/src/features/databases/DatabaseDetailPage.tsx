import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Columns3, Plus, Settings2, Sigma, Table2, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "@/lib/api";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { iconFor } from "@/lib/iconPicker";
import { toast } from "@/lib/toast";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field } from "@/components/ui/Field";
import { Kanban, KanbanColumnDef } from "@/components/ui/Kanban";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SearchInput } from "@/components/ui/SearchInput";
import { Skeleton, SkeletonRows } from "@/components/ui/Skeleton";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { RecordPagePanel } from "@/features/recordpages/RecordPagePanel";
import { CellDisplay, PropertyInput } from "./cells";
import {
  cellValue,
  changedProps,
  DbProperty,
  DbRecordEnvelope,
  formulaExpression,
  optionsJsonFrom,
  parseOptions,
  PROPERTY_TYPES,
  PropertyType,
  recordTitleOf,
  toValuePayload,
  TYPE_LABEL,
} from "./propertyTypes";

interface DbMeta {
  database: { id: number; name: string; icon: string | null; color: string | null; description: string | null };
  properties: DbProperty[];
  canEdit?: boolean;
  canDelete?: boolean;
}

export default function DatabaseDetailPage() {
  const { id } = useParams();
  const dbId = Number(id);
  const qc = useQueryClient();
  const role = useAuthStore((s) => s.user?.role);
  const [view, setView] = useState<"table" | "board">("table");
  const [boardPropId, setBoardPropId] = useState<number | null>(null);
  const [search, setSearch] = useState("");
  const [q, setQ] = useState("");
  const [propsOpen, setPropsOpen] = useState(false);
  const [recordDrawer, setRecordDrawer] = useState<DbRecordEnvelope | null>(null);
  const [creatingRecord, setCreatingRecord] = useState(false);
  const [initialValues, setInitialValues] = useState<Record<number, unknown>>({});
  const [draftValues, setDraftValues] = useState<Record<number, unknown>>({});
  const [newProp, setNewProp] = useState<{ name: string; type: PropertyType; options: string; relationDbId: string; expression: string }>({
    name: "",
    type: "text",
    options: "",
    relationDbId: "",
    expression: "",
  });
  const { confirm, node: confirmNode } = useConfirm();

  useEffect(() => {
    const t = setTimeout(() => setQ(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  const meta = useQuery({
    queryKey: ["database", dbId],
    queryFn: async () => (await api.get<DbMeta>(`/databases/${dbId}`)).data,
  });
  const records = useQuery({
    queryKey: ["database", dbId, "records", { q }],
    queryFn: async () => (await api.get<DbRecordEnvelope[]>(`/databases/${dbId}/records`, { params: { q: q || undefined, size: 500 } })).data,
  });
  const allDbs = useQuery({
    queryKey: ["databases"],
    queryFn: async () => (await api.get<{ id: number; name: string }[]>("/databases")).data,
    enabled: propsOpen,
  });

  const canEdit = !isReadOnly(role) && meta.data?.canEdit !== false;
  const properties = useMemo(() => [...(meta.data?.properties ?? [])].sort((a, b) => a.position - b.position), [meta.data]);
  const selectProps = properties.filter((p) => p.type === "select");
  const boardProp = selectProps.find((p) => p.id === boardPropId) ?? selectProps[0];

  const invalidateRecords = () => qc.invalidateQueries({ queryKey: ["database", dbId, "records"] });
  const fail = (e: Error) => toast.error(e.message);

  const addProperty = useMutation({
    mutationFn: async () => {
      const body: Record<string, unknown> = { name: newProp.name, type: newProp.type, position: properties.length };
      if (newProp.type === "select" || newProp.type === "multi_select") body.optionsJson = optionsJsonFrom(newProp.options);
      if (newProp.type === "relation") body.relationDbId = Number(newProp.relationDbId);
      if (newProp.type === "formula") body.optionsJson = JSON.stringify({ expression: newProp.expression });
      return api.post(`/databases/${dbId}/properties`, body);
    },
    onSuccess: () => {
      toast.success("Property added");
      qc.invalidateQueries({ queryKey: ["database", dbId] });
      invalidateRecords();
      setNewProp({ name: "", type: "text", options: "", relationDbId: "", expression: "" });
    },
    onError: fail,
  });

  const deleteProperty = useMutation({
    mutationFn: async (propId: number) => api.delete(`/databases/${dbId}/properties/${propId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["database", dbId] });
      invalidateRecords();
    },
    onError: fail,
  });

  const openEdit = (env: DbRecordEnvelope) => {
    const vals: Record<number, unknown> = {};
    properties.filter((p) => p.type !== "formula").forEach((p) => (vals[p.id] = cellValue(env, p)));
    setInitialValues(vals);
    setDraftValues(vals);
    setRecordDrawer(env);
  };

  const openCreate = () => {
    const vals: Record<number, unknown> = {};
    properties
      .filter((p) => p.type !== "formula")
      .forEach((p) => (vals[p.id] = p.type === "checkbox" ? false : ["multi_select", "relation", "file"].includes(p.type) ? [] : ""));
    setInitialValues({});
    setDraftValues(vals);
    setCreatingRecord(true);
  };

  const closeDrawers = () => {
    setRecordDrawer(null);
    setCreatingRecord(false);
  };

  const saveRecord = useMutation({
    mutationFn: async () => {
      const byId = new Map(properties.map((p) => [p.id, p]));
      // On update only the properties that changed are sent (the API leaves the rest untouched).
      const ids = recordDrawer ? changedProps(initialValues, draftValues) : Object.keys(draftValues).map(Number);
      const values = ids
        .map((pid) => byId.get(pid))
        .filter((p): p is DbProperty => !!p && p.type !== "formula")
        .filter((p) => recordDrawer || !(draftValues[p.id] === "" || (Array.isArray(draftValues[p.id]) && (draftValues[p.id] as unknown[]).length === 0)) || p.required)
        .map((p) => toValuePayload(p.id, p.type, draftValues[p.id]));
      if (recordDrawer) return (await api.put<DbRecordEnvelope>(`/databases/${dbId}/records/${recordDrawer.record.id}`, { values })).data;
      return (await api.post<DbRecordEnvelope>(`/databases/${dbId}/records`, { values })).data;
    },
    onSuccess: (env) => {
      toast.success(recordDrawer ? "Record updated" : "Record created");
      invalidateRecords();
      if (recordDrawer) closeDrawers();
      else {
        setCreatingRecord(false);
        openEdit(env);
      }
    },
    onError: fail,
  });

  const deleteRecord = useMutation({
    mutationFn: async (recId: number) => api.delete(`/databases/${dbId}/records/${recId}`),
    onSuccess: () => {
      toast.success("Record deleted");
      invalidateRecords();
      closeDrawers();
    },
    onError: fail,
  });

  // ── Board view grouping ──────────────────────────────────────────────────
  const boardOptions = boardProp ? parseOptions(boardProp.optionsJson) : [];
  const boardColumns: KanbanColumnDef[] = [
    ...boardOptions.map((o) => ({ key: o.label, label: o.label, accent: o.color })),
    { key: "__none", label: "No value", accent: "#94a3b8" },
  ];
  type BoardCard = DbRecordEnvelope & { id: number };
  const boardItems: Record<string, BoardCard[]> = useMemo(() => {
    if (!boardProp) return {};
    const map: Record<string, BoardCard[]> = { __none: [] };
    const opts = parseOptions(boardProp.optionsJson);
    opts.forEach((o) => (map[o.label] = []));
    (records.data ?? []).forEach((env) => {
      const raw = cellValue(env, boardProp) as string | null;
      const label = opts.find((o) => o.id === raw || o.label === raw)?.label ?? "__none";
      (map[label] ??= []).push({ ...env, id: env.record.id });
    });
    return map;
  }, [records.data, boardProp]);

  async function moveCard(recId: number, _from: string, to: string) {
    if (!boardProp || !canEdit) return;
    try {
      await api.put(`/databases/${dbId}/records/${recId}`, {
        values: [{ propertyId: boardProp.id, textValue: to === "__none" ? null : to }],
      });
      invalidateRecords();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not move record");
    }
  }

  const recordTitle = (env: DbRecordEnvelope) => recordTitleOf(env, properties);

  if (meta.isLoading) return <Skeleton className="h-[60vh] rounded-2xl" />;
  if (meta.isError) {
    return (
      <div className="glass-card">
        <QueryError error={meta.error} onRetry={() => meta.refetch()} title="Couldn't load this database" />
      </div>
    );
  }
  const Icon = iconFor(meta.data?.database.icon);
  const color = meta.data?.database.color || "#1ca3e6";
  const previewProps = properties.filter((p) => p.id !== boardProp?.id).slice(0, 3);

  return (
    <div className="flex flex-col gap-4 flex-1 min-h-0">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-3 min-w-0">
          <Link to="/databases" className="btn-icon shrink-0" aria-label="Back to databases">
            <ArrowLeft size={17} />
          </Link>
          <div className="h-10 w-10 rounded-xl flex items-center justify-center shrink-0" style={{ backgroundColor: `${color}22`, color }}>
            <Icon size={18} />
          </div>
          <div className="min-w-0">
            <h1 className="text-lg font-bold truncate" style={{ color: "var(--ink-primary)" }}>
              {meta.data?.database.name}
            </h1>
            <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
              {records.data?.length ?? 0} records · {properties.length} properties
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button className="btn-secondary" onClick={() => setPropsOpen(true)}>
            <Settings2 size={15} /> Properties
          </button>
          {canEdit && (
            <button className="btn-primary" onClick={openCreate} disabled={properties.length === 0}>
              <Plus size={15} /> New record
            </button>
          )}
        </div>
      </div>

      {properties.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={Settings2}
            title="Add your first property"
            description="Properties are the columns of this database — text, numbers, dates, selects, relations, formulas and more."
            action={
              canEdit && (
                <button className="btn-primary" onClick={() => setPropsOpen(true)}>
                  <Plus size={15} /> Add property
                </button>
              )
            }
          />
        </div>
      ) : (
        <>
          <div className="flex flex-col sm:flex-row sm:items-center gap-3 justify-between">
            <div className="flex items-center gap-2 flex-wrap">
              <Tabs
                value={view}
                onChange={setView}
                tabs={[
                  { value: "table", label: "Table" },
                  ...(boardProp ? [{ value: "board" as const, label: "Board" }] : []),
                ]}
              />
              {view === "board" && selectProps.length > 1 && (
                <select
                  className="select w-auto py-1.5 text-sm"
                  value={boardProp?.id ?? ""}
                  onChange={(e) => setBoardPropId(Number(e.target.value))}
                  aria-label="Group board by"
                >
                  {selectProps.map((p) => (
                    <option key={p.id} value={p.id}>
                      Group by {p.name}
                    </option>
                  ))}
                </select>
              )}
            </div>
            <SearchInput value={search} onChange={setSearch} placeholder="Search records…" className="sm:max-w-xs w-full" />
          </div>

          {records.isError ? (
            <div className="glass-card">
              <QueryError error={records.error} onRetry={() => records.refetch()} title="Couldn't load records" />
            </div>
          ) : view === "table" ? (
            <div className="glass-card overflow-hidden">
              {records.isLoading ? (
                <SkeletonRows rows={6} cols={Math.min(properties.length, 5)} />
              ) : (
                <div className="overflow-x-auto thin-scroll">
                  <table className="data-table">
                    <thead>
                      <tr>
                        {properties.map((p) => (
                          <th key={p.id} className="whitespace-nowrap">
                            <span className="inline-flex items-center gap-1">
                              {p.type === "formula" && <Sigma size={11} />}
                              {p.name}
                            </span>
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {(records.data ?? []).length === 0 ? (
                        <tr>
                          <td colSpan={properties.length} className="text-center py-12 text-sm" style={{ color: "var(--ink-muted)" }}>
                            {q ? "No records match your search." : canEdit ? 'No records yet — click "New record" to add one.' : "No records yet."}
                          </td>
                        </tr>
                      ) : (
                        records.data!.map((env) => (
                          <tr key={env.record.id} onClick={() => openEdit(env)}>
                            {properties.map((p, i) => (
                              <td key={p.id} className={`text-sm ${i === 0 ? "font-medium" : ""}`} style={{ maxWidth: "18rem" }}>
                                <CellDisplay prop={p} env={env} />
                              </td>
                            ))}
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          ) : (
            <div className="flex-1 min-h-[480px]">
              <Kanban
                columns={boardColumns}
                itemsByColumn={boardItems}
                loading={records.isLoading}
                draggable={canEdit}
                onMove={(recId, from, to) => moveCard(recId, from, to)}
                renderCard={(env) => (
                  <div onClick={() => openEdit(env)} className="space-y-2">
                    <p className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                      {recordTitle(env)}
                    </p>
                    {previewProps
                      .filter((p) => p.type !== "text" || p.id !== properties.find((x) => x.type === "text")?.id)
                      .map((p) => (
                        <div key={p.id} className="flex items-center justify-between gap-2 text-xs">
                          <span style={{ color: "var(--ink-muted)" }}>{p.name}</span>
                          <span className="min-w-0 truncate text-right">
                            <CellDisplay prop={p} env={env} />
                          </span>
                        </div>
                      ))}
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
        width="36rem"
        headerExtra={
          recordDrawer &&
          canEdit && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              aria-label="Delete record"
              onClick={async () => {
                const ok = await confirm("Delete this record?", "It will be moved to trash.");
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
            <Field key={p.id} label={`${p.name} · ${TYPE_LABEL[p.type]}`} required={p.required}>
              <PropertyInput
                prop={p}
                value={draftValues[p.id]}
                onChange={(v) => setDraftValues((d) => ({ ...d, [p.id]: v }))}
                recordId={recordDrawer?.record.id ?? null}
                env={recordDrawer}
                disabled={!canEdit}
              />
            </Field>
          ))}
        </div>
        <div className="flex items-center justify-end gap-2 mt-4 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            {canEdit ? "Cancel" : "Close"}
          </button>
          {canEdit && (
            <button className="btn-primary" disabled={saveRecord.isPending} onClick={() => saveRecord.mutate()}>
              {recordDrawer ? "Save changes" : "Create record"}
            </button>
          )}
        </div>
        {recordDrawer && (
          <>
            <div className="mt-8 pt-5 hairline border-t">
              <p className="field-label">Page</p>
              <RecordPagePanel relatedType="DatabaseRecord" relatedId={recordDrawer.record.id} readOnly={!canEdit} />
            </div>
            <div className="mt-4 pt-4 hairline border-t">
              <AttachmentList relatedType="DatabaseRecord" relatedId={recordDrawer.record.id} readOnly={!canEdit} />
            </div>
          </>
        )}
      </Drawer>

      {/* Properties management */}
      <Modal open={propsOpen} onClose={() => setPropsOpen(false)} title="Properties" width="30rem">
        <div className="space-y-4">
          <div className="space-y-1 max-h-60 overflow-y-auto thin-scroll">
            {properties.map((p) => (
              <div key={p.id} className="flex items-center justify-between gap-2 py-1.5 px-2 rounded-lg hover:bg-[rgb(var(--panel-border)/0.05)]">
                <div className="min-w-0">
                  <p className="text-sm font-medium truncate">{p.name}</p>
                  <p className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
                    {TYPE_LABEL[p.type]}
                    {p.type === "formula" && ` · ${formulaExpression(p.optionsJson)}`}
                  </p>
                </div>
                {canEdit && (
                  <button
                    className="btn-icon h-7 w-7"
                    style={{ color: "var(--critical)" }}
                    aria-label={`Delete property ${p.name}`}
                    onClick={async () => {
                      const ok = await confirm(`Delete "${p.name}"?`, "Its values are removed from every record.");
                      if (ok) deleteProperty.mutate(p.id);
                    }}
                  >
                    <Trash2 size={13} />
                  </button>
                )}
              </div>
            ))}
          </div>
          {canEdit && (
            <div className="pt-3 hairline border-t space-y-3">
              <p className="field-label">Add property</p>
              <input
                className="input"
                placeholder="Property name"
                value={newProp.name}
                onChange={(e) => setNewProp((d) => ({ ...d, name: e.target.value }))}
              />
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
              {newProp.type === "relation" && (
                <select
                  className="select"
                  value={newProp.relationDbId}
                  onChange={(e) => setNewProp((d) => ({ ...d, relationDbId: e.target.value }))}
                  aria-label="Related database"
                >
                  <option value="">Link to database…</option>
                  {(allDbs.data ?? []).map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name}
                    </option>
                  ))}
                </select>
              )}
              {newProp.type === "formula" && (
                <div>
                  <input
                    className="input font-mono text-sm"
                    placeholder='e.g. {Price} * {Qty}  or  IF({Score} > 50, "Hot", "Cold")'
                    value={newProp.expression}
                    onChange={(e) => setNewProp((d) => ({ ...d, expression: e.target.value }))}
                  />
                  <p className="text-[11px] mt-1" style={{ color: "var(--ink-muted)" }}>
                    Use {"{Field}"} or prop("Field"), + − × ÷, & to join text, comparisons and IF(cond, a, b).
                  </p>
                </div>
              )}
              <button
                className="btn-primary w-full"
                disabled={
                  !newProp.name.trim() ||
                  addProperty.isPending ||
                  (newProp.type === "relation" && !newProp.relationDbId) ||
                  (newProp.type === "formula" && !newProp.expression.trim())
                }
                onClick={() => addProperty.mutate()}
              >
                {newProp.type === "formula" ? <Sigma size={14} /> : newProp.type === "relation" ? <Columns3 size={14} /> : <Table2 size={14} />} Add
                property
              </button>
            </div>
          )}
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}
