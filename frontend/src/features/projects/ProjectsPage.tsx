import { CalendarDays, FolderKanban, Plus, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useEntityCrud, useUsersLookup } from "@/lib/hooks";
import { formatDate } from "@/lib/format";
import type { Project } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StageBadge } from "@/components/ui/Badge";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

const STATUSES = ["active", "on_hold", "completed", "cancelled"];
const PRIORITIES = ["low", "normal", "high", "urgent"];

export default function ProjectsPage() {
  const [selected, setSelected] = useState<Project | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<Project>>({});
  const users = useUsersLookup();
  const { confirm, node: confirmNode } = useConfirm();

  const { list, create, update, remove } = useEntityCrud<Project>("projects", { label: "Project" });

  useEffect(() => {
    setDraft(selected ?? { status: "active", priority: "normal" });
  }, [selected, creating]);

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name;
  const closeDrawers = () => {
    setSelected(null);
    setCreating(false);
  };
  const save = () => {
    if (selected) update.mutate({ id: selected.id, payload: draft }, { onSuccess: closeDrawers });
    else create.mutate(draft, { onSuccess: closeDrawers });
  };

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Projects"
        description={`${list.data?.count ?? 0} projects`}
        icon={<FolderKanban size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New project
          </button>
        }
      />

      {list.isLoading ? (
        <SkeletonCards count={6} />
      ) : (list.data?.data ?? []).length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={FolderKanban} title="No projects yet" description="Group related work with milestones and a team." />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {list.data!.data.map((p) => (
            <button key={p.id} onClick={() => setSelected(p)} className="glass-card-hover p-4 text-left">
              <div className="flex items-start justify-between gap-2">
                <div
                  className="h-9 w-9 rounded-xl flex items-center justify-center text-sm font-bold text-white shrink-0"
                  style={{ backgroundColor: p.coverColor || "var(--brand-500)" }}
                >
                  {p.name.slice(0, 1).toUpperCase()}
                </div>
                <StageBadge value={p.status.replace("_", " ")} />
              </div>
              <p className="mt-3 text-sm font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                {p.name}
              </p>
              {p.description && (
                <p className="text-xs mt-1 line-clamp-2" style={{ color: "var(--ink-muted)" }}>
                  {p.description}
                </p>
              )}
              <div className="flex items-center justify-between mt-4 pt-3 hairline border-t">
                {p.dueDate ? (
                  <span className="inline-flex items-center gap-1 text-[11px]" style={{ color: "var(--ink-muted)" }}>
                    <CalendarDays size={11} /> {formatDate(p.dueDate)}
                  </span>
                ) : (
                  <span />
                )}
                <Avatar name={userName(p.ownerId)} size={22} />
              </div>
            </button>
          ))}
        </div>
      )}

      <Drawer
        open={!!selected || creating}
        onClose={closeDrawers}
        title={selected ? selected.name : "New project"}
        headerExtra={
          selected && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              onClick={async () => {
                const ok = await confirm("Delete this project?", `"${selected.name}" will be moved to trash.`);
                if (ok) remove.mutate({ id: selected.id }, { onSuccess: closeDrawers });
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" value={draft.name ?? ""} onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
          </Field>
          <Field label="Description">
            <textarea className="textarea" value={draft.description ?? ""} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
          </Field>
          <FieldGrid>
            <Field label="Status">
              <select className="select" value={draft.status ?? "active"} onChange={(e) => setDraft((d) => ({ ...d, status: e.target.value }))}>
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {s.replace("_", " ")}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Priority">
              <select className="select" value={draft.priority ?? "normal"} onChange={(e) => setDraft((d) => ({ ...d, priority: e.target.value }))}>
                {PRIORITIES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Owner">
              <select
                className="select"
                value={draft.ownerId ?? ""}
                onChange={(e) => setDraft((d) => ({ ...d, ownerId: e.target.value ? Number(e.target.value) : undefined }))}
              >
                <option value="">— Unassigned —</option>
                {users.data?.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Cover color">
              <input
                type="color"
                className="input h-10"
                value={draft.coverColor ?? "#1ca3e6"}
                onChange={(e) => setDraft((d) => ({ ...d, coverColor: e.target.value }))}
              />
            </Field>
            <Field label="Start date">
              <input className="input" type="date" value={draft.startDate ?? ""} onChange={(e) => setDraft((d) => ({ ...d, startDate: e.target.value }))} />
            </Field>
            <Field label="Due date">
              <input className="input" type="date" value={draft.dueDate ?? ""} onChange={(e) => setDraft((d) => ({ ...d, dueDate: e.target.value }))} />
            </Field>
            <Field label="Budget (₹)">
              <input
                className="input"
                type="number"
                value={draft.budget ?? ""}
                onChange={(e) => setDraft((d) => ({ ...d, budget: e.target.value ? Number(e.target.value) : undefined }))}
              />
            </Field>
            <Field label="Tags" hint="Comma-separated">
              <input className="input" value={draft.tags ?? ""} onChange={(e) => setDraft((d) => ({ ...d, tags: e.target.value }))} />
            </Field>
          </FieldGrid>
        </div>
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={closeDrawers}>
            Cancel
          </button>
          <button className="btn-primary" disabled={!draft.name} onClick={save}>
            {selected ? "Save changes" : "Create project"}
          </button>
        </div>
      </Drawer>
      {confirmNode}
    </div>
  );
}
