import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Plus, Shield, UserPlus, Users as UsersIcon } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { colorFor, roleLabel } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Role, User } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Drawer } from "@/components/ui/Drawer";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { SearchInput } from "@/components/ui/SearchInput";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { EmptyState } from "@/components/ui/EmptyState";

const ROLES: Role[] = ["SUPER_ADMIN", "VP_SALES", "MANAGER", "EXECUTIVE", "INSIDE_SALES", "FIELD_SALES", "SUPPORT"];

export default function TeamPage() {
  const currentUser = useAuthStore((s) => s.user);
  const isAdmin = currentUser?.role === "SUPER_ADMIN";
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<User | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<User> & { password?: string }>({});
  const [resetOpen, setResetOpen] = useState(false);
  const [newPassword, setNewPassword] = useState("");
  const qc = useQueryClient();

  const list = useQuery({
    queryKey: ["users"],
    queryFn: async () => (await api.get<User[]>("/users")).data,
  });

  useEffect(() => {
    setDraft(selected ?? { role: "EXECUTIVE", color: "#1ca3e6" });
  }, [selected, creating]);

  const createUser = useMutation({
    mutationFn: async () => (await api.post("/users", draft)).data,
    onSuccess: () => {
      toast.success("User created");
      qc.invalidateQueries({ queryKey: ["users"] });
      setCreating(false);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const updateUser = useMutation({
    mutationFn: async () => (await api.put(`/users/${selected!.id}`, draft)).data,
    onSuccess: () => {
      toast.success("User updated");
      qc.invalidateQueries({ queryKey: ["users"] });
      setSelected(null);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const resetPassword = useMutation({
    mutationFn: async () => api.post(`/users/${selected!.id}/reset-password`, { newPassword }),
    onSuccess: () => {
      toast.success("Password reset");
      setResetOpen(false);
      setNewPassword("");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const clearReportsTo = useMutation({
    mutationFn: async () => api.post(`/users/${selected!.id}/clear-reports-to`),
    onSuccess: () => {
      toast.success("Reporting line cleared");
      qc.invalidateQueries({ queryKey: ["users"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const filtered = (list.data ?? []).filter((u) => u.name.toLowerCase().includes(search.toLowerCase()) || u.email.toLowerCase().includes(search.toLowerCase()));
  const canEditSelected = selected && (isAdmin || selected.id === currentUser?.id);
  const isSelfOnly = selected && !isAdmin && selected.id === currentUser?.id;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Team"
        description={`${list.data?.length ?? 0} members in your organization`}
        icon={<UsersIcon size={20} />}
        actions={
          isAdmin && (
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <UserPlus size={15} /> Invite member
            </button>
          )
        }
      />
      <SearchInput value={search} onChange={setSearch} placeholder="Search team…" className="max-w-xs" />

      {list.isLoading ? (
        <SkeletonCards count={8} />
      ) : filtered.length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={UsersIcon} title="No team members found" />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
          {filtered.map((u) => (
            <button key={u.id} onClick={() => setSelected(u)} className="glass-card-hover p-4 text-left">
              <div className="flex items-start justify-between">
                <Avatar name={u.name} color={u.color} size={42} />
                {!u.active && <span className="badge-critical">Inactive</span>}
              </div>
              <p className="mt-3 text-sm font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                {u.name}
              </p>
              <p className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
                {u.email}
              </p>
              <div className="flex items-center gap-1.5 mt-2.5">
                <Shield size={11} style={{ color: "var(--brand-600)" }} />
                <span className="text-xs font-medium" style={{ color: "var(--brand-600)" }}>
                  {roleLabel(u.role)}
                </span>
              </div>
              {u.territory && (
                <p className="text-[11px] mt-1" style={{ color: "var(--ink-muted)" }}>
                  Territory: {u.territory}
                </p>
              )}
            </button>
          ))}
        </div>
      )}

      {/* Edit / view drawer */}
      <Drawer open={!!selected} onClose={() => setSelected(null)} title={selected?.name ?? ""} subtitle={selected?.email}>
        {selected && (
          <div className="space-y-5">
            <FieldGrid>
              <Field label="Name">
                <input
                  className="input"
                  disabled={!canEditSelected}
                  value={draft.name ?? ""}
                  onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
                />
              </Field>
              <Field label="Initials">
                <input
                  className="input"
                  disabled={!canEditSelected}
                  maxLength={4}
                  value={draft.initials ?? ""}
                  onChange={(e) => setDraft((d) => ({ ...d, initials: e.target.value.toUpperCase() }))}
                />
              </Field>
              <Field label="Color">
                <input
                  className="input h-10"
                  type="color"
                  disabled={!canEditSelected}
                  value={draft.color ?? colorFor(selected.name)}
                  onChange={(e) => setDraft((d) => ({ ...d, color: e.target.value }))}
                />
              </Field>
              <Field label="Role" hint={!isAdmin ? "Only Super Admin can change this" : undefined}>
                <select
                  className="select"
                  disabled={!isAdmin}
                  value={draft.role ?? selected.role}
                  onChange={(e) => setDraft((d) => ({ ...d, role: e.target.value as Role }))}
                >
                  {ROLES.map((r) => (
                    <option key={r} value={r}>
                      {roleLabel(r)}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Territory" hint={!isAdmin ? "Only Super Admin can change this" : undefined}>
                <input
                  className="input"
                  disabled={!isAdmin}
                  value={draft.territory ?? ""}
                  onChange={(e) => setDraft((d) => ({ ...d, territory: e.target.value }))}
                />
              </Field>
              <Field label="Reports to" hint={!isAdmin ? "Only Super Admin can change this" : undefined}>
                <select
                  className="select"
                  disabled={!isAdmin}
                  value={draft.reportsToId ?? ""}
                  onChange={(e) => setDraft((d) => ({ ...d, reportsToId: e.target.value ? Number(e.target.value) : null }))}
                >
                  <option value="">— None —</option>
                  {list.data
                    ?.filter((u) => u.id !== selected.id)
                    .map((u) => (
                      <option key={u.id} value={u.id}>
                        {u.name}
                      </option>
                    ))}
                </select>
              </Field>
              {isAdmin && (
                <Field label="Active">
                  <select
                    className="select"
                    value={String(draft.active ?? selected.active)}
                    onChange={(e) => setDraft((d) => ({ ...d, active: e.target.value === "true" }))}
                  >
                    <option value="true">Active</option>
                    <option value="false">Deactivated</option>
                  </select>
                </Field>
              )}
            </FieldGrid>

            <div className="flex flex-wrap gap-2 pt-2 hairline border-t">
              {(isAdmin || selected.id === currentUser?.id) && (
                <button className="btn-secondary" onClick={() => setResetOpen(true)}>
                  <KeyRound size={14} /> Reset password
                </button>
              )}
              {isAdmin && selected.reportsToId && (
                <button className="btn-secondary" onClick={() => clearReportsTo.mutate()}>
                  Clear reporting line
                </button>
              )}
            </div>

            {(canEditSelected || isSelfOnly) && (
              <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
                <button className="btn-ghost" onClick={() => setSelected(null)}>
                  Cancel
                </button>
                <button className="btn-primary" onClick={() => updateUser.mutate()}>
                  Save changes
                </button>
              </div>
            )}
          </div>
        )}
      </Drawer>

      {/* Create drawer */}
      <Drawer open={creating} onClose={() => setCreating(false)} title="Invite team member">
        <div className="space-y-5">
          <FieldGrid>
            <Field label="Name" required>
              <input className="input" value={draft.name ?? ""} onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
            </Field>
            <Field label="Email" required>
              <input className="input" type="email" value={draft.email ?? ""} onChange={(e) => setDraft((d) => ({ ...d, email: e.target.value }))} />
            </Field>
            <Field label="Temporary password" required hint="At least 8 characters">
              <input
                className="input"
                type="password"
                value={draft.password ?? ""}
                onChange={(e) => setDraft((d) => ({ ...d, password: e.target.value }))}
              />
            </Field>
            <Field label="Role">
              <select className="select" value={draft.role ?? "EXECUTIVE"} onChange={(e) => setDraft((d) => ({ ...d, role: e.target.value as Role }))}>
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {roleLabel(r)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Territory">
              <input className="input" value={draft.territory ?? ""} onChange={(e) => setDraft((d) => ({ ...d, territory: e.target.value }))} />
            </Field>
            <Field label="Reports to">
              <select
                className="select"
                value={draft.reportsToId ?? ""}
                onChange={(e) => setDraft((d) => ({ ...d, reportsToId: e.target.value ? Number(e.target.value) : null }))}
              >
                <option value="">— None —</option>
                {list.data?.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name}
                  </option>
                ))}
              </select>
            </Field>
          </FieldGrid>
          <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
            <button className="btn-ghost" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button
              className="btn-primary"
              disabled={!draft.name || !draft.email || !draft.password || draft.password.length < 8}
              onClick={() => createUser.mutate()}
            >
              <Plus size={14} /> Create member
            </button>
          </div>
        </div>
      </Drawer>

      <Modal open={resetOpen} onClose={() => setResetOpen(false)} title="Reset password" width="24rem">
        <div className="space-y-4">
          <Field label="New password" hint="At least 8 characters">
            <input className="input" type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
          </Field>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setResetOpen(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={newPassword.length < 8} onClick={() => resetPassword.mutate()}>
              Reset
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
