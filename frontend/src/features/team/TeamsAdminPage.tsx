import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2, UserPlus, Users2 } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useUsersLookup } from "@/lib/hooks";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

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

export default function TeamsAdminPage() {
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<{ name: string; description: string; leaderId: number | ""; color: string }>({
    name: "",
    description: "",
    leaderId: "",
    color: "#1ca3e6",
  });
  const [managing, setManaging] = useState<TeamRow | null>(null);
  const [addUserId, setAddUserId] = useState<number | "">("");
  const users = useUsersLookup();
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["teams"],
    queryFn: async () => (await api.get<TeamRow[]>("/teams")).data,
  });

  useEffect(() => {
    if (managing) {
      const fresh = list.data?.find((t) => t.id === managing.id);
      if (fresh) setManaging(fresh);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list.data]);

  const createTeam = useMutation({
    mutationFn: async () =>
      (await api.post("/teams", { name: draft.name, description: draft.description, leaderId: draft.leaderId || null, color: draft.color })).data,
    onSuccess: () => {
      toast.success("Team created");
      qc.invalidateQueries({ queryKey: ["teams"] });
      setCreating(false);
      setDraft({ name: "", description: "", leaderId: "", color: "#1ca3e6" });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteTeam = useMutation({
    mutationFn: async (id: number) => api.delete(`/teams/${id}`),
    onSuccess: () => {
      toast.success("Team deleted");
      qc.invalidateQueries({ queryKey: ["teams"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const addMember = useMutation({
    mutationFn: async () => api.post(`/teams/${managing!.id}/members`, { userId: addUserId }),
    onSuccess: () => {
      toast.success("Member added");
      qc.invalidateQueries({ queryKey: ["teams"] });
      setAddUserId("");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const removeMember = useMutation({
    mutationFn: async (userId: number) => api.delete(`/teams/${managing!.id}/members/${userId}`),
    onSuccess: () => {
      toast.success("Member removed");
      qc.invalidateQueries({ queryKey: ["teams"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const availableUsers = users.data?.filter((u) => !managing?.members.some((m) => m.userId === u.id)) ?? [];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Teams"
        description={`${list.data?.length ?? 0} teams`}
        icon={<Users2 size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New team
          </button>
        }
      />

      {list.isLoading ? (
        <SkeletonCards count={4} />
      ) : (list.data ?? []).length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={Users2} title="No teams yet" description="Group reps under a Manager for team-scoped visibility." />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {list.data!.map((t) => (
            <div key={t.id} className="glass-card p-4">
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-2">
                  <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: t.color || "var(--brand-500)" }} />
                  <h3 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                    {t.name}
                  </h3>
                </div>
                <button
                  className="btn-icon h-7 w-7"
                  style={{ color: "var(--critical)" }}
                  onClick={async () => {
                    const ok = await confirm("Delete this team?", `"${t.name}" and its roster will be removed.`);
                    if (ok) deleteTeam.mutate(t.id);
                  }}
                >
                  <Trash2 size={14} />
                </button>
              </div>
              {t.description && (
                <p className="text-xs mt-1.5" style={{ color: "var(--ink-muted)" }}>
                  {t.description}
                </p>
              )}
              {t.leader && (
                <div className="flex items-center gap-2 mt-3">
                  <Avatar name={t.leader.name} color={t.leader.color} size={22} />
                  <span className="text-xs" style={{ color: "var(--ink-secondary)" }}>
                    Led by {t.leader.name}
                  </span>
                </div>
              )}
              <div className="flex items-center justify-between mt-4 pt-3 hairline border-t">
                <div className="flex items-center -space-x-2">
                  {t.members.slice(0, 5).map((m) => (
                    <Avatar key={m.id} name={m.name} color={m.color} size={24} />
                  ))}
                  {t.memberCount === 0 && (
                    <span className="text-xs" style={{ color: "var(--ink-muted)" }}>
                      No members yet
                    </span>
                  )}
                </div>
                <button className="text-xs font-semibold" style={{ color: "var(--brand-600)" }} onClick={() => setManaging(t)}>
                  Manage →
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal open={creating} onClose={() => setCreating(false)} title="New team" width="26rem">
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" value={draft.name} onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
          </Field>
          <Field label="Description">
            <textarea className="textarea" value={draft.description} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
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
              <input type="color" className="input h-10" value={draft.color} onChange={(e) => setDraft((d) => ({ ...d, color: e.target.value }))} />
            </Field>
          </FieldGrid>
          <div className="flex justify-end gap-2 pt-2">
            <button className="btn-ghost" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!draft.name} onClick={() => createTeam.mutate()}>
              Create team
            </button>
          </div>
        </div>
      </Modal>

      <Modal open={!!managing} onClose={() => setManaging(null)} title={managing ? `${managing.name} — roster` : ""} width="28rem">
        {managing && (
          <div className="space-y-4">
            <div className="flex gap-2">
              <select className="select" value={addUserId} onChange={(e) => setAddUserId(e.target.value ? Number(e.target.value) : "")}>
                <option value="">Select a user to add…</option>
                {availableUsers.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                  </option>
                ))}
              </select>
              <button className="btn-primary px-3" disabled={!addUserId} onClick={() => addMember.mutate()}>
                <UserPlus size={15} />
              </button>
            </div>
            <div className="space-y-1.5 max-h-72 overflow-y-auto thin-scroll">
              {managing.members.length === 0 ? (
                <p className="text-sm text-center py-6" style={{ color: "var(--ink-muted)" }}>
                  No members yet
                </p>
              ) : (
                managing.members.map((m) => (
                  <div key={m.id} className="flex items-center gap-2.5 py-2 hairline border-b last:border-b-0">
                    <Avatar name={m.name} color={m.color} size={26} />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate">{m.name}</p>
                      <p className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
                        {m.email}
                      </p>
                    </div>
                    <button className="btn-icon h-7 w-7" style={{ color: "var(--critical)" }} onClick={() => removeMember.mutate(m.userId)}>
                      <Trash2 size={13} />
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
        )}
      </Modal>
      {confirmNode}
    </div>
  );
}
