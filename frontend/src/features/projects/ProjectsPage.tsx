import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarDays, Flag, FolderKanban, Plus, Trash2, UserPlus, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { useUsersLookup } from "@/lib/hooks";
import { formatDate, isOverdue } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Project } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { Avatar, AvatarStack } from "@/components/ui/Avatar";
import { PriorityBadge, StageBadge } from "@/components/ui/Badge";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { QueryError } from "@/components/ui/QueryError";
import { SearchInput } from "@/components/ui/SearchInput";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";

const STATUSES = ["active", "on_hold", "completed", "cancelled"];
const PRIORITIES = ["low", "normal", "high", "urgent"];

interface ProjectStats {
  milestones: number;
  milestonesDone: number;
  members: number;
  tasks: number;
  tasksDone: number;
  memberIds: number[];
}
interface Milestone {
  id: number;
  name: string;
  dueDate: string | null;
  completed: boolean;
  position: number;
}
interface Member {
  id: number;
  userId: number;
  role: string;
}

type Filter = "all" | "active" | "on_hold" | "completed";

function progressOf(s: ProjectStats | undefined): number | null {
  if (!s) return null;
  if (s.milestones > 0) return Math.round((s.milestonesDone / s.milestones) * 100);
  if (s.tasks > 0) return Math.round((s.tasksDone / s.tasks) * 100);
  return null;
}

export default function ProjectsPage() {
  const qc = useQueryClient();
  const me = useAuthStore((s) => s.user);
  const readOnly = isReadOnly(me?.role);
  const [filter, setFilter] = useState<Filter>("all");
  const [search, setSearch] = useState("");
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<Project>>({});
  const users = useUsersLookup();
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["projects"],
    queryFn: async () => (await api.get<Project[]>("/projects", { params: { size: 500 } })).data,
  });
  const ids = (list.data ?? []).map((p) => p.id);
  const stats = useQuery({
    queryKey: ["projects", "summary", ids],
    queryFn: async () => (await api.get<Record<string, ProjectStats>>("/projects/summary", { params: { ids: ids.join(",") } })).data,
    enabled: ids.length > 0,
  });

  const selected = list.data?.find((p) => p.id === selectedId) ?? null;
  const userName = (id: number | null | undefined) => users.data?.find((u) => u.id === id)?.name;
  const canEdit = (p: Project | null) => !readOnly && (!p || isManagerUp(me?.role) || p.ownerId === me?.id);

  useEffect(() => {
    setDraft(selected ?? { status: "active", priority: "normal" });
  }, [selected, creating]);

  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (list.data ?? []).filter(
      (p) => (filter === "all" || p.status === filter) && (!q || p.name.toLowerCase().includes(q) || (p.tags ?? "").toLowerCase().includes(q))
    );
  }, [list.data, filter, search]);

  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    (list.data ?? []).forEach((p) => (c[p.status] = (c[p.status] ?? 0) + 1));
    return c;
  }, [list.data]);

  const invalidate = () => qc.invalidateQueries({ queryKey: ["projects"] });
  const close = () => {
    setSelectedId(null);
    setCreating(false);
  };

  const save = useMutation({
    mutationFn: async () => {
      if (selected) return (await api.put<Project>(`/projects/${selected.id}`, draft)).data;
      return (await api.post<Project>("/projects", draft)).data;
    },
    onSuccess: (p) => {
      toast.success(selected ? "Project updated" : "Project created");
      invalidate();
      setCreating(false);
      setSelectedId(p.id);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/projects/${id}`),
    onSuccess: () => {
      toast.success("Project deleted");
      close();
      invalidate();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Projects"
        description={`${list.data?.length ?? 0} ${(list.data?.length ?? 0) === 1 ? "project" : "projects"} · track milestones, people and files`}
        icon={<FolderKanban size={20} />}
        actions={
          !readOnly && (
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <Plus size={15} /> New project
            </button>
          )
        }
      />

      <div className="flex flex-col sm:flex-row sm:items-center gap-3 justify-between">
        <div className="overflow-x-auto thin-scroll -mx-1 px-1">
          <Tabs<Filter>
            value={filter}
            onChange={setFilter}
            tabs={[
              { value: "all", label: "All", count: list.data?.length ?? 0 },
              { value: "active", label: "Active", count: counts.active ?? 0 },
              { value: "on_hold", label: "On hold", count: counts.on_hold ?? 0 },
              { value: "completed", label: "Completed", count: counts.completed ?? 0 },
            ]}
          />
        </div>
        <SearchInput value={search} onChange={setSearch} placeholder="Search projects…" className="sm:max-w-xs w-full" />
      </div>

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load projects" />
        </div>
      ) : list.isLoading ? (
        <SkeletonCards count={6} />
      ) : visible.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={FolderKanban}
            title={(list.data ?? []).length === 0 ? "No projects yet" : "No projects match"}
            description="Group related work with milestones, a team and shared files."
            action={
              !readOnly &&
              (list.data ?? []).length === 0 && (
                <button className="btn-primary" onClick={() => setCreating(true)}>
                  <Plus size={15} /> New project
                </button>
              )
            }
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {visible.map((p) => {
            const s = stats.data?.[String(p.id)];
            const pct = progressOf(s);
            const color = p.coverColor || "var(--brand-500)";
            const overdue = p.dueDate && p.status !== "completed" && isOverdue(p.dueDate);
            return (
              <button key={p.id} onClick={() => setSelectedId(p.id)} className="glass-card-hover text-left overflow-hidden flex flex-col">
                <div className="h-1.5 w-full" style={{ background: color }} />
                <div className="p-4 flex flex-col gap-3 flex-1">
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex items-center gap-2.5 min-w-0">
                      <div
                        className="h-9 w-9 rounded-xl flex items-center justify-center text-sm font-bold text-white shrink-0"
                        style={{ background: color }}
                      >
                        {p.name.slice(0, 1).toUpperCase()}
                      </div>
                      <div className="min-w-0">
                        <p className="text-sm font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                          {p.name}
                        </p>
                        <p className="text-[11px] truncate" style={{ color: "var(--ink-muted)" }}>
                          {userName(p.ownerId) ?? "Owner"}
                        </p>
                      </div>
                    </div>
                    <StageBadge value={p.status.replace("_", " ")} />
                  </div>
                  {p.description && (
                    <p className="text-xs line-clamp-2" style={{ color: "var(--ink-secondary)" }}>
                      {p.description}
                    </p>
                  )}
                  <div className="mt-auto">
                    <div className="flex items-center justify-between text-[11px] mb-1.5" style={{ color: "var(--ink-muted)" }}>
                      <span>
                        {s && s.milestones > 0
                          ? `${s.milestonesDone}/${s.milestones} milestones`
                          : s && s.tasks > 0
                          ? `${s.tasksDone}/${s.tasks} tasks`
                          : "No milestones yet"}
                      </span>
                      <span className="font-semibold" style={{ color: "var(--ink-secondary)" }}>
                        {pct != null ? `${pct}%` : ""}
                      </span>
                    </div>
                    <div className="h-1.5 rounded-full overflow-hidden" style={{ backgroundColor: "rgb(var(--panel-border) / 0.08)" }}>
                      <div className="h-full rounded-full transition-all duration-500" style={{ width: `${pct ?? 0}%`, background: color }} />
                    </div>
                  </div>
                  <div className="flex items-center justify-between pt-3 hairline border-t">
                    <span
                      className="inline-flex items-center gap-1 text-[11px]"
                      style={{ color: overdue ? "var(--critical)" : "var(--ink-muted)" }}
                    >
                      <CalendarDays size={11} /> {p.dueDate ? formatDate(p.dueDate) : "No due date"}
                    </span>
                    <div className="flex items-center gap-2">
                      {p.priority !== "normal" && <PriorityBadge value={p.priority} />}
                      <AvatarStack names={(s?.memberIds ?? [p.ownerId]).map((id) => userName(id))} max={4} />
                    </div>
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      )}

      <Drawer
        open={!!selected || creating}
        onClose={close}
        title={selected ? selected.name : "New project"}
        subtitle={selected ? `${selected.status.replace("_", " ")} · ${selected.priority} priority` : undefined}
        width="34rem"
        headerExtra={
          selected &&
          canEdit(selected) && (
            <button
              className="btn-icon"
              style={{ color: "var(--critical)" }}
              aria-label="Delete project"
              onClick={async () => {
                const ok = await confirm("Delete this project?", `"${selected.name}" will be deleted; its tasks stay but are unlinked.`);
                if (ok) remove.mutate(selected.id);
              }}
            >
              <Trash2 size={16} />
            </button>
          )
        }
      >
        <fieldset disabled={!canEdit(selected)} className="space-y-4 min-w-0">
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
                <option value="">— Me —</option>
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
              <input className="input" type="date" value={draft.startDate ?? ""} onChange={(e) => setDraft((d) => ({ ...d, startDate: e.target.value || null }))} />
            </Field>
            <Field label="Due date">
              <input className="input" type="date" value={draft.dueDate ?? ""} onChange={(e) => setDraft((d) => ({ ...d, dueDate: e.target.value || null }))} />
            </Field>
            <Field label="Budget (₹)">
              <input
                className="input"
                type="number"
                value={draft.budget ?? ""}
                onChange={(e) => setDraft((d) => ({ ...d, budget: e.target.value ? Number(e.target.value) : null }))}
              />
            </Field>
            <Field label="Tags" hint="Comma-separated">
              <input className="input" value={draft.tags ?? ""} onChange={(e) => setDraft((d) => ({ ...d, tags: e.target.value }))} />
            </Field>
          </FieldGrid>
        </fieldset>
        {canEdit(selected) && (
          <div className="flex items-center justify-end gap-2 mt-5">
            <button className="btn-ghost" onClick={close}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!draft.name?.trim() || save.isPending} onClick={() => save.mutate()}>
              {selected ? "Save changes" : "Create project"}
            </button>
          </div>
        )}

        {selected && (
          <div className="mt-8 space-y-8">
            <MilestonesPanel projectId={selected.id} readOnly={readOnly} />
            <MembersPanel projectId={selected.id} ownerId={selected.ownerId} canManage={canEdit(selected)} />
            <AttachmentList relatedType="Project" relatedId={selected.id} readOnly={readOnly} />
          </div>
        )}
      </Drawer>
      {confirmNode}
    </div>
  );
}

function MilestonesPanel({ projectId, readOnly }: { projectId: number; readOnly: boolean }) {
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [due, setDue] = useState("");
  const key = ["projects", projectId, "milestones"];
  const milestones = useQuery({
    queryKey: key,
    queryFn: async () => (await api.get<Milestone[]>(`/projects/${projectId}/milestones`)).data,
  });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: key });
    qc.invalidateQueries({ queryKey: ["projects", "summary"] });
  };
  const add = useMutation({
    mutationFn: async () =>
      api.post(`/projects/${projectId}/milestones`, { name, dueDate: due || null, position: milestones.data?.length ?? 0 }),
    onSuccess: () => {
      setName("");
      setDue("");
      refresh();
    },
    onError: (e: Error) => toast.error(e.message),
  });
  const toggle = useMutation({
    mutationFn: async (m: Milestone) => api.put(`/projects/${projectId}/milestones/${m.id}`, { completed: !m.completed }),
    onSuccess: refresh,
    onError: (e: Error) => toast.error(e.message),
  });
  const remove = useMutation({
    mutationFn: async (m: Milestone) => api.delete(`/projects/${projectId}/milestones/${m.id}`),
    onSuccess: refresh,
    onError: (e: Error) => toast.error(e.message),
  });
  const done = (milestones.data ?? []).filter((m) => m.completed).length;
  const total = milestones.data?.length ?? 0;

  return (
    <section>
      <div className="flex items-center justify-between mb-2">
        <p className="field-label mb-0">
          <Flag size={11} className="inline -mt-0.5 mr-1" /> Milestones
        </p>
        {total > 0 && (
          <span className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
            {done}/{total} done
          </span>
        )}
      </div>
      {milestones.isError ? (
        <QueryError error={milestones.error} onRetry={() => milestones.refetch()} compact />
      ) : (
        <ul className="space-y-1">
          {(milestones.data ?? []).map((m) => (
            <li key={m.id} className="flex items-center gap-2.5 py-1.5 px-2 rounded-lg group hover:bg-[rgb(var(--panel-border)/0.04)]">
              <input type="checkbox" className="checkbox" checked={m.completed} disabled={readOnly} onChange={() => toggle.mutate(m)} aria-label={`Complete ${m.name}`} />
              <span
                className="flex-1 text-sm truncate"
                style={{ color: m.completed ? "var(--ink-muted)" : "var(--ink-primary)", textDecoration: m.completed ? "line-through" : undefined }}
              >
                {m.name}
              </span>
              {m.dueDate && (
                <span className="text-[11px]" style={{ color: !m.completed && isOverdue(m.dueDate) ? "var(--critical)" : "var(--ink-muted)" }}>
                  {formatDate(m.dueDate)}
                </span>
              )}
              {!readOnly && (
                <button
                  className="btn-icon h-6 w-6 sm:opacity-0 sm:group-hover:opacity-100"
                  style={{ color: "var(--critical)" }}
                  aria-label={`Delete ${m.name}`}
                  onClick={() => remove.mutate(m)}
                >
                  <X size={12} />
                </button>
              )}
            </li>
          ))}
          {total === 0 && !milestones.isLoading && (
            <li className="text-xs py-2" style={{ color: "var(--ink-muted)" }}>
              No milestones yet.
            </li>
          )}
        </ul>
      )}
      {!readOnly && (
        <form
          className="flex flex-col sm:flex-row gap-2 mt-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (name.trim()) add.mutate();
          }}
        >
          <input className="input flex-1" placeholder="Add a milestone…" value={name} onChange={(e) => setName(e.target.value)} />
          <input className="input sm:w-40" type="date" value={due} onChange={(e) => setDue(e.target.value)} aria-label="Milestone due date" />
          <button className="btn-secondary" type="submit" disabled={!name.trim() || add.isPending}>
            <Plus size={14} /> Add
          </button>
        </form>
      )}
    </section>
  );
}

function MembersPanel({ projectId, ownerId, canManage }: { projectId: number; ownerId: number; canManage: boolean }) {
  const qc = useQueryClient();
  const users = useUsersLookup();
  const [userId, setUserId] = useState("");
  const [role, setRole] = useState("member");
  const key = ["projects", projectId, "members"];
  const members = useQuery({
    queryKey: key,
    queryFn: async () => (await api.get<Member[]>(`/projects/${projectId}/members`)).data,
  });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: key });
    qc.invalidateQueries({ queryKey: ["projects", "summary"] });
  };
  const add = useMutation({
    mutationFn: async () => api.post(`/projects/${projectId}/members`, { userId: Number(userId), role }),
    onSuccess: () => {
      setUserId("");
      refresh();
    },
    onError: (e: Error) => toast.error(e.message),
  });
  const remove = useMutation({
    mutationFn: async (uid: number) => api.delete(`/projects/${projectId}/members/${uid}`),
    onSuccess: refresh,
    onError: (e: Error) => toast.error(e.message),
  });
  const memberIds = new Set((members.data ?? []).map((m) => m.userId));
  const name = (id: number) => users.data?.find((u) => u.id === id)?.name ?? `User #${id}`;

  return (
    <section>
      <p className="field-label">Team</p>
      {members.isError ? (
        <QueryError error={members.error} onRetry={() => members.refetch()} compact />
      ) : (
        <ul className="space-y-1">
          {(members.data ?? []).map((m) => (
            <li key={m.id} className="flex items-center gap-2.5 py-1.5 px-2 rounded-lg group">
              <Avatar name={name(m.userId)} size={26} />
              <span className="flex-1 text-sm truncate" style={{ color: "var(--ink-primary)" }}>
                {name(m.userId)}
              </span>
              <span className="badge-neutral capitalize">{m.role}</span>
              {canManage && m.userId !== ownerId && (
                <button
                  className="btn-icon h-6 w-6 sm:opacity-0 sm:group-hover:opacity-100"
                  aria-label={`Remove ${name(m.userId)}`}
                  onClick={() => remove.mutate(m.userId)}
                >
                  <X size={12} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canManage && (
        <div className="flex flex-col sm:flex-row gap-2 mt-2">
          <select className="select flex-1" value={userId} onChange={(e) => setUserId(e.target.value)} aria-label="Add member">
            <option value="">Add someone…</option>
            {users.data
              ?.filter((u) => !memberIds.has(u.id))
              .map((u) => (
                <option key={u.id} value={u.id}>
                  {u.name}
                </option>
              ))}
          </select>
          <select className="select sm:w-32" value={role} onChange={(e) => setRole(e.target.value)} aria-label="Member role">
            {["manager", "member", "viewer"].map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
          <button className="btn-secondary" disabled={!userId || add.isPending} onClick={() => add.mutate()}>
            <UserPlus size={14} /> Add
          </button>
        </div>
      )}
    </section>
  );
}
