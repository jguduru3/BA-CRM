import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Crown, Pencil, Plus, Trash2, UserMinus, UserPlus, Users2 } from "lucide-react";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { useUsersLookup } from "@/lib/hooks";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SkeletonCards } from "@/components/ui/Skeleton";

interface TeamMember {
  id: number;
  userId: number;
  role: string;
  name: string | null;
  email: string | null;
  initials: string | null;
  color: string | null;
}
interface TeamRow {
  id: number;
  name: string;
  description: string | null;
  leaderId: number | null;
  leader: { id: number; name: string; initials: string | null; color: string | null } | null;
  color: string | null;
  active: boolean;
  memberCount: number;
  members: TeamMember[];
}

interface Draft {
  name: string;
  description: string;
  leaderId: number | "";
  color: string;
}
const EMPTY: Draft = { name: "", description: "", leaderId: "", color: "#1ca3e6" };

export default function TeamsAdminPage() {
  const role = useAuthStore((s) => s.user?.role);
  // Backend: TEAM create/edit/delete = Super Admin or VP Sales; Managers can only view.
  const canManage = role === "SUPER_ADMIN" || role === "VP_SALES";
  const [editing, setEditing] = useState<TeamRow | "new" | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [managingId, setManagingId] = useState<number | null>(null);
  const [addUserId, setAddUserId] = useState<number | "">("");
  const users = useUsersLookup();
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["teams"],
    queryFn: async () => (await api.get<TeamRow[]>("/teams")).data,
  });
  const teams = useMemo(() => [...(list.data ?? [])].sort((a, b) => a.name.localeCompare(b.name)), [list.data]);
  const managing = managingId != null ? teams.find((t) => t.id === managingId) ?? null : null;
  const invalidate = () => qc.invalidateQueries({ queryKey: ["teams"] });

  const saveTeam = useMutation({
    mutationFn: async () => {
      const body = {
        name: draft.name.trim(),
        description: draft.description.trim(),
        leaderId: draft.leaderId || null,
        color: draft.color,
      };
      if (editing === "new") return (await api.post("/teams", body)).data;
      return (await api.put(`/teams/${(editing as TeamRow).id}`, body)).data;
    },
    onSuccess: () => {
      toast.success(editing === "new" ? "Team created" : "Team updated");
      invalidate();
      setEditing(null);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteTeam = useMutation({
    mutationFn: async (id: number) => api.delete(`/teams/${id}`),
    onSuccess: () => {
      toast.success("Team deleted");
      invalidate();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const addMember = useMutation({
    mutationFn: async () => api.post(`/teams/${managingId}/members`, { userId: addUserId }),
    onSuccess: () => {
      toast.success("Member added");
      invalidate();
      setAddUserId("");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const removeMember = useMutation({
    mutationFn: async (userId: number) => api.delete(`/teams/${managingId}/members/${userId}`),
    onSuccess: () => {
      toast.success("Member removed");
      invalidate();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  function openEditor(t: TeamRow | "new") {
    setDraft(
      t === "new"
        ? EMPTY
        : { name: t.name, description: t.description ?? "", leaderId: t.leaderId ?? "", color: t.color ?? "#1ca3e6" }
    );
    setEditing(t);
  }

  async function onDelete(t: TeamRow) {
    const ok = await confirm(`Delete “${t.name}”?`, "The team and its roster are removed. Members keep their accounts and records.");
    if (ok) deleteTeam.mutate(t.id);
  }
  async function onRemoveMember(m: TeamMember) {
    const ok = await confirm(
      `Remove ${m.name ?? "this member"}?`,
      `They leave “${managing?.name}”, and the team's manager loses team-scoped visibility of their records.`
    );
    if (ok) removeMember.mutate(m.userId);
  }

  const availableUsers = users.data?.filter((u) => !managing?.members.some((m) => m.userId === u.id)) ?? [];
  const totalMembers = teams.reduce((n, t) => n + t.memberCount, 0);

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Teams"
        description={list.isSuccess ? `${teams.length} team${teams.length === 1 ? "" : "s"} · ${totalMembers} memberships` : "Groups that drive team-scoped visibility"}
        icon={<Users2 size={20} />}
        actions={
          canManage && (
            <button className="btn-primary" onClick={() => openEditor("new")}>
              <Plus size={15} /> New team
            </button>
          )
        }
      />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load teams" />
        </div>
      ) : list.isLoading ? (
        <SkeletonCards count={6} />
      ) : teams.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={Users2}
            title="No teams yet"
            description="Group reps under a Manager for team-scoped visibility."
            action={
              canManage && (
                <button className="btn-primary" onClick={() => openEditor("new")}>
                  <Plus size={15} /> Create a team
                </button>
              )
            }
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {teams.map((t) => (
            <article key={t.id} className="glass-card p-4 sm:p-4 flex flex-col">
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2.5 min-w-0">
                  <span
                    className="h-9 w-9 rounded-xl flex items-center justify-center shrink-0 text-white"
                    style={{ backgroundColor: t.color || "var(--brand-500)" }}
                    aria-hidden
                  >
                    <Users2 size={16} />
                  </span>
                  <div className="min-w-0">
                    <h3 className="text-sm font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                      {t.name}
                    </h3>
                    <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                      {t.memberCount} member{t.memberCount === 1 ? "" : "s"}
                      {!t.active && " · inactive"}
                    </p>
                  </div>
                </div>
                {canManage && (
                  <div className="flex items-center -mr-1.5 -mt-1 shrink-0">
                    <button className="btn-icon" aria-label={`Edit ${t.name}`} title="Edit" onClick={() => openEditor(t)}>
                      <Pencil size={14} />
                    </button>
                    <button
                      className="btn-icon"
                      aria-label={`Delete ${t.name}`}
                      title="Delete"
                      style={{ color: "var(--critical)" }}
                      onClick={() => void onDelete(t)}
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                )}
              </div>
              {t.description && (
                <p className="text-sm mt-3 line-clamp-2" style={{ color: "var(--ink-secondary)" }}>
                  {t.description}
                </p>
              )}
              <div className="flex items-center gap-2 mt-3">
                {t.leader ? (
                  <>
                    <Avatar name={t.leader.name} color={t.leader.color} size={22} />
                    <span className="text-xs" style={{ color: "var(--ink-secondary)" }}>
                      Led by <span className="font-medium">{t.leader.name}</span>
                    </span>
                  </>
                ) : (
                  <span className="text-xs" style={{ color: "var(--ink-muted)" }}>
                    No leader assigned
                  </span>
                )}
              </div>
              <div className="flex items-center justify-between gap-2 mt-auto pt-4">
                <div className="flex items-center -space-x-2 min-h-[24px] pt-3">
                  {t.members.slice(0, 5).map((m) => (
                    <span key={m.id} className="rounded-full ring-2 ring-[var(--surface-solid)]">
                      <Avatar name={m.name} color={m.color} size={24} />
                    </span>
                  ))}
                  {t.memberCount > 5 && (
                    <span className="pl-3 text-xs" style={{ color: "var(--ink-muted)" }}>
                      +{t.memberCount - 5}
                    </span>
                  )}
                  {t.memberCount === 0 && (
                    <span className="text-xs" style={{ color: "var(--ink-muted)" }}>
                      No members yet
                    </span>
                  )}
                </div>
                <button className="btn-ghost text-xs font-semibold mt-3 min-h-[36px]" style={{ color: "var(--brand-600)" }} onClick={() => setManagingId(t.id)}>
                  {canManage ? "Manage roster" : "View roster"} →
                </button>
              </div>
            </article>
          ))}
        </div>
      )}

      <Modal
        open={!!editing}
        onClose={() => setEditing(null)}
        title={editing === "new" ? "New team" : "Edit team"}
        width="30rem"
        footer={
          <>
            <button className="btn-ghost" onClick={() => setEditing(null)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!draft.name.trim() || saveTeam.isPending} onClick={() => saveTeam.mutate()}>
              {saveTeam.isPending ? "Saving…" : editing === "new" ? "Create team" : "Save changes"}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" autoFocus maxLength={150} value={draft.name} onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
          </Field>
          <Field label="Description">
            <textarea className="textarea" maxLength={1000} value={draft.description} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
          </Field>
          <FieldGrid>
            <Field label="Leader">
              <select
                className="select"
                value={draft.leaderId}
                onChange={(e) => setDraft((d) => ({ ...d, leaderId: e.target.value ? Number(e.target.value) : "" }))}
              >
                <option value="">— None —</option>
                {users.data?.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Color">
              <input type="color" className="input h-10 p-1" value={draft.color} onChange={(e) => setDraft((d) => ({ ...d, color: e.target.value }))} />
            </Field>
          </FieldGrid>
        </div>
      </Modal>

      <Modal open={!!managing} onClose={() => setManagingId(null)} title={managing ? `${managing.name} · roster` : ""} width="30rem">
        {managing && (
          <div className="space-y-4">
            {canManage && (
              <div className="flex gap-2">
                <select
                  className="select"
                  aria-label="User to add"
                  value={addUserId}
                  onChange={(e) => setAddUserId(e.target.value ? Number(e.target.value) : "")}
                >
                  <option value="">Select a person to add…</option>
                  {availableUsers.map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.name}
                    </option>
                  ))}
                </select>
                <button className="btn-primary shrink-0" disabled={!addUserId || addMember.isPending} onClick={() => addMember.mutate()}>
                  <UserPlus size={15} /> Add
                </button>
              </div>
            )}
            <ul className="max-h-80 overflow-y-auto thin-scroll -mx-1 px-1">
              {managing.members.length === 0 ? (
                <li className="text-sm text-center py-8" style={{ color: "var(--ink-muted)" }}>
                  No members yet
                </li>
              ) : (
                managing.members.map((m) => (
                  <li key={m.id} className="flex items-center gap-3 py-2.5 hairline border-b last:border-b-0">
                    <Avatar name={m.name} color={m.color} size={30} />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate flex items-center gap-1.5" style={{ color: "var(--ink-primary)" }}>
                        {m.name}
                        {managing.leaderId === m.userId && <Crown size={12} style={{ color: "var(--warning)" }} aria-label="Team leader" />}
                      </p>
                      <p className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
                        {m.email}
                      </p>
                    </div>
                    {canManage && (
                      <button
                        className="btn-icon"
                        aria-label={`Remove ${m.name ?? "member"}`}
                        title="Remove from team"
                        style={{ color: "var(--critical)" }}
                        disabled={removeMember.isPending}
                        onClick={() => void onRemoveMember(m)}
                      >
                        <UserMinus size={15} />
                      </button>
                    )}
                  </li>
                ))
              )}
            </ul>
          </div>
        )}
      </Modal>
      {confirmNode}
    </div>
  );
}
