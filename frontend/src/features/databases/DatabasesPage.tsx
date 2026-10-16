import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Database, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "@/lib/api";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { QueryError } from "@/components/ui/QueryError";
import { ICON_PICKS, iconFor } from "@/lib/iconPicker";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

export interface CustomDb {
  id: number;
  name: string;
  icon: string | null;
  color: string | null;
  description: string | null;
}

const COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#4a3aa7", "#e34948"];

export default function DatabasesPage() {
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<CustomDb>>({ color: COLORS[0] });
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { confirm, node: confirmNode } = useConfirm();
  const role = useAuthStore((s) => s.user?.role);
  const readOnly = isReadOnly(role);
  const canDelete = role === "SUPER_ADMIN" || role === "VP_SALES";

  const list = useQuery({
    queryKey: ["databases"],
    queryFn: async () => (await api.get<CustomDb[]>("/databases")).data,
  });

  const create = useMutation({
    mutationFn: async () => (await api.post<CustomDb>("/databases", draft)).data,
    onSuccess: (db) => {
      toast.success("Database created");
      qc.invalidateQueries({ queryKey: ["databases"] });
      setCreating(false);
      navigate(`/databases/${db.id}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/databases/${id}`),
    onSuccess: () => {
      toast.success("Database deleted");
      qc.invalidateQueries({ queryKey: ["databases"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Databases"
        description="Notion-style tables and boards with typed properties, built on your own schema."
        icon={<Database size={20} />}
        actions={
          !readOnly && (
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <Plus size={15} /> New database
            </button>
          )
        }
      />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load databases" />
        </div>
      ) : list.isLoading ? (
        <SkeletonCards count={6} />
      ) : (list.data ?? []).length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={Database} title="No databases yet" description="Build a custom table for anything the core CRM doesn't model." />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {list.data!.map((db) => (
            <div key={db.id} className="glass-card-hover p-4 text-left relative group">
              <button className="absolute inset-0" onClick={() => navigate(`/databases/${db.id}`)} aria-label={`Open ${db.name}`} />
              <div className="flex items-start justify-between relative pointer-events-none">
                <div
                  className="h-10 w-10 rounded-xl flex items-center justify-center"
                  style={{ backgroundColor: `${db.color || "#1ca3e6"}22`, color: db.color || "var(--brand-600)" }}
                >
                  {(() => {
                    const Icon = iconFor(db.icon);
                    return <Icon size={18} strokeWidth={2} />;
                  })()}
                </div>
                {canDelete && (
                <button
                  aria-label={`Delete ${db.name}`}
                  className="btn-icon h-7 w-7 sm:opacity-0 sm:group-hover:opacity-100 pointer-events-auto"
                  style={{ color: "var(--critical)" }}
                  onClick={async (e) => {
                    e.stopPropagation();
                    const ok = await confirm("Delete this database?", `"${db.name}" and all of its properties, views and records will be deleted.`);
                    if (ok) remove.mutate(db.id);
                  }}
                >
                  <Trash2 size={14} />
                </button>
                )}
              </div>
              <p className="mt-3 text-sm font-semibold relative pointer-events-none" style={{ color: "var(--ink-primary)" }}>
                {db.name}
              </p>
              {db.description && (
                <p className="text-xs mt-1 line-clamp-2 relative pointer-events-none" style={{ color: "var(--ink-muted)" }}>
                  {db.description}
                </p>
              )}
            </div>
          ))}
        </div>
      )}

      <Modal open={creating} onClose={() => setCreating(false)} title="New database" width="24rem">
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" value={draft.name ?? ""} onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
          </Field>
          <Field label="Description">
            <textarea className="textarea" value={draft.description ?? ""} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
          </Field>
          <FieldGrid>
            <Field label="Icon">
              <div className="flex gap-1.5 flex-wrap">
                {ICON_PICKS.map(({ key, icon: Icon }) => (
                  <button
                    key={key}
                    type="button"
                    onClick={() => setDraft((d) => ({ ...d, icon: key }))}
                    className="h-9 w-9 rounded-lg flex items-center justify-center"
                    style={{
                      backgroundColor: draft.icon === key ? "rgb(var(--ring-brand) / 0.15)" : "rgb(var(--panel-border) / 0.06)",
                      color: draft.icon === key ? "var(--brand-600)" : "var(--ink-secondary)",
                      outline: draft.icon === key ? "2px solid var(--brand-500)" : undefined,
                    }}
                  >
                    <Icon size={16} />
                  </button>
                ))}
              </div>
            </Field>
            <Field label="Color">
              <div className="flex gap-1.5 flex-wrap items-center">
                {COLORS.map((c) => (
                  <button
                    key={c}
                    type="button"
                    onClick={() => setDraft((d) => ({ ...d, color: c }))}
                    className="h-7 w-7 rounded-full"
                    style={{ backgroundColor: c, outline: draft.color === c ? "2px solid var(--ink-primary)" : undefined, outlineOffset: 2 }}
                  />
                ))}
              </div>
            </Field>
          </FieldGrid>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!draft.name} onClick={() => create.mutate()}>
              Create database
            </button>
          </div>
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}
