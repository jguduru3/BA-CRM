import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, MapPin, Plus, Shield, UserCheck, UserPlus, Users as UsersIcon, UserX } from "lucide-react";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "@/lib/api";
import { isAdmin as isAdminRole, useAuthStore } from "@/lib/auth";
import { colorFor, formatRelative, roleLabel } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Role, User } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { useReason } from "@/components/ui/ReasonDialog";
import { SearchInput } from "@/components/ui/SearchInput";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { passwordChecks, PasswordRules } from "@/features/auth/passwordRules";

const ROLES: Role[] = ["SUPER_ADMIN", "VP_SALES", "MANAGER", "EXECUTIVE", "INSIDE_SALES", "FIELD_SALES", "SUPPORT"];

type StatusFilter = "active" | "inactive" | "all";

interface EditDraft {
  name: string;
  initials: string;
  color: string;
  role: Role;
  territory: string;
  reportsToId: number | null;
}

interface CreateDraft {
  name: string;
  email: string;
  password: string;
  role: Role;
  territory: string;
  reportsToId: number | null;
}

const EMPTY_CREATE: CreateDraft = { name: "", email: "", password: "", role: "EXECUTIVE", territory: "", reportsToId: null };

export default function TeamPage() {
  const currentUser = useAuthStore((s) => s.user);
  const isAdmin = isAdminRole(currentUser?.role);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();
  const { askReason, node: reasonNode } = useReason();

  const [search, setSearch] = useState("");
  const [roleFilter, setRoleFilter] = useState<Role | "">("");
  const [status, setStatus] = useState<StatusFilter>("active");
  const [selected, setSelected] = useState<User | null>(null);
  const [edit, setEdit] = useState<EditDraft | null>(null);
  const [creating, setCreating] = useState(false);
  const [create, setCreate] = useState<CreateDraft>(EMPTY_CREATE);
  const [resetOpen, setResetOpen] = useState(false);
  const [newPassword, setNewPassword] = useState("");

  const list = useQuery({
    queryKey: ["users"],
    queryFn: async () => (await api.get<User[]>("/users")).data,
  });
  const users = useMemo(() => list.data ?? [], [list.data]);
  const byId = useMemo(() => new Map(users.map((u) => [u.id, u])), [users]);

  const invalidate = () => qc.invalidateQueries({ queryKey: ["users"] });

  function open(u: User) {
    setSelected(u);
    setEdit({
      name: u.name,
      initials: u.initials ?? "",
      color: u.color ?? colorFor(u.name),
      role: u.role,
      territory: u.territory ?? "",
      reportsToId: u.reportsToId,
    });
  }
  function close() {
    setSelected(null);
    setEdit(null);
  }

  const createUser = useMutation({
    mutationFn: async () =>
      (
        await api.post<User>("/users", {
          name: create.name.trim(),
          email: create.email.trim(),
          password: create.password,
          role: create.role,
          territory: create.territory.trim() || undefined,
          reportsToId: create.reportsToId ?? undefined,
        })
      ).data,
    onSuccess: (u) => {
      toast.success(`${u.name} added — they'll set their own password at first sign-in`);
      invalidate();
      setCreating(false);
      setCreate(EMPTY_CREATE);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const updateUser = useMutation({
    mutationFn: async () => {
      if (!selected || !edit) return null;
      // Only send what the caller may change — the API rejects role/active/territory/
      // reporting-line fields from anyone but a Super Admin, even when unchanged.
      const body: Record<string, unknown> = {
        name: edit.name.trim(),
        initials: edit.initials.trim() || undefined,
        color: edit.color,
      };
      if (isAdmin) {
        if (edit.role !== selected.role) body.role = edit.role;
        if (edit.territory !== (selected.territory ?? "")) body.territory = edit.territory.trim();
        if (edit.reportsToId != null && edit.reportsToId !== selected.reportsToId) body.reportsToId = edit.reportsToId;
      }
      return (await api.put<User>(`/users/${selected.id}`, body)).data;
    },
    onSuccess: (u) => {
      toast.success("Profile saved");
      if (u && u.id === currentUser?.id) useAuthStore.getState().updateUser({ ...currentUser, ...u });
      invalidate();
      close();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const setActive = useMutation({
    mutationFn: async ({ user, active, reason }: { user: User; active: boolean; reason?: string }) =>
      // The API ignores unknown fields; the reason is sent for future audit use.
      (await api.put<User>(`/users/${user.id}`, { active, reason })).data,
    onSuccess: (u) => {
      toast.success(u.active ? `${u.name} reactivated` : `${u.name} deactivated and signed out`);
      invalidate();
      setSelected(u);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const resetPassword = useMutation({
    mutationFn: async () => api.post(`/users/${selected!.id}/reset-password`, { newPassword }),
    onSuccess: () => {
      toast.success("Password reset — they'll choose a new one at next sign-in");
      setResetOpen(false);
      setNewPassword("");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const clearReportsTo = useMutation({
    mutationFn: async () => (await api.post<User>(`/users/${selected!.id}/clear-reports-to`)).data,
    onSuccess: (u) => {
      toast.success("Reporting line cleared");
      invalidate();
      open(u);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return users
      .filter((u) => (status === "all" ? true : status === "active" ? u.active : !u.active))
      .filter((u) => !roleFilter || u.role === roleFilter)
      .filter((u) => !q || u.name.toLowerCase().includes(q) || u.email.toLowerCase().includes(q) || (u.territory ?? "").toLowerCase().includes(q))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [users, search, roleFilter, status]);

  const activeCount = users.filter((u) => u.active).length;
  const isSelf = !!selected && selected.id === currentUser?.id;
  const canEditSelected = !!selected && (isAdmin || isSelf);
  const pwChecks = passwordChecks(newPassword, selected?.email);
  const createChecks = passwordChecks(create.password, create.email);
  const createValid = create.name.trim() && /.+@.+\..+/.test(create.email.trim()) && createChecks.every((c) => c.ok);

  async function onDeactivate(u: User) {
    const reason = await askReason({
      title: `Deactivate ${u.name}?`,
      description: "They're signed out everywhere and can't sign in until reactivated. Their records stay as they are.",
      label: "Reason",
      confirmLabel: "Deactivate",
      danger: true,
    });
    if (reason) setActive.mutate({ user: u, active: false, reason });
  }
  async function onReactivate(u: User) {
    if (await confirm(`Reactivate ${u.name}?`, "They'll be able to sign in again with their existing password.", false)) {
      setActive.mutate({ user: u, active: true });
    }
  }
  async function onClearReportsTo(u: User) {
    if (await confirm("Clear reporting line?", `${u.name} will no longer report to anyone. Team-scoped visibility for their manager changes immediately.`)) {
      clearReportsTo.mutate();
    }
  }
  async function onResetPassword() {
    if (!selected) return;
    if (await confirm(`Reset ${selected.name}'s password?`, "Their current sessions end and they must choose a new password at next sign-in.")) {
      resetPassword.mutate();
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="Team"
        description={list.isSuccess ? `${activeCount} active · ${users.length - activeCount} deactivated` : "People in your organization"}
        icon={<UsersIcon size={20} />}
        actions={
          isAdmin && (
            <button className="btn-primary" onClick={() => setCreating(true)}>
              <UserPlus size={15} /> Add member
            </button>
          )
        }
      />

      <div className="flex flex-col sm:flex-row sm:items-center gap-2.5">
        <SearchInput value={search} onChange={setSearch} placeholder="Search name, email, territory…" className="w-full sm:max-w-xs" />
        <div className="grid grid-cols-2 sm:flex gap-2.5">
          <select className="select sm:w-44" value={roleFilter} onChange={(e) => setRoleFilter(e.target.value as Role | "")} aria-label="Filter by role">
            <option value="">All roles</option>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {roleLabel(r)}
              </option>
            ))}
          </select>
          <select className="select sm:w-36" value={status} onChange={(e) => setStatus(e.target.value as StatusFilter)} aria-label="Filter by status">
            <option value="active">Active</option>
            <option value="inactive">Deactivated</option>
            <option value="all">Everyone</option>
          </select>
        </div>
      </div>

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load the team" />
        </div>
      ) : list.isLoading ? (
        <SkeletonCards count={8} />
      ) : filtered.length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={UsersIcon}
            title={users.length === 0 ? "No team members yet" : "No one matches these filters"}
            description={users.length === 0 ? undefined : "Try another search, role or status."}
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
          {filtered.map((u) => {
            const manager = u.reportsToId ? byId.get(u.reportsToId) : undefined;
            return (
              <button
                key={u.id}
                onClick={() => open(u)}
                className={`glass-card-hover p-4 text-left focus-brand flex flex-col ${u.active ? "" : "opacity-70"}`}
              >
                <div className="flex items-start justify-between gap-2 w-full">
                  <Avatar name={u.name} color={u.color} size={42} />
                  {!u.active ? (
                    <span className="badge-critical">Deactivated</span>
                  ) : u.id === currentUser?.id ? (
                    <span className="badge-brand">You</span>
                  ) : null}
                </div>
                <p className="mt-3 text-sm font-semibold truncate w-full" style={{ color: "var(--ink-primary)" }}>
                  {u.name}
                </p>
                <p className="text-xs truncate w-full" style={{ color: "var(--ink-muted)" }}>
                  {u.email}
                </p>
                <div className="flex items-center gap-1.5 mt-3">
                  <Shield size={12} style={{ color: "var(--brand-600)" }} />
                  <span className="text-xs font-medium" style={{ color: "var(--brand-600)" }}>
                    {roleLabel(u.role)}
                  </span>
                </div>
                <div className="mt-1.5 space-y-0.5 text-[11px] w-full" style={{ color: "var(--ink-muted)" }}>
                  {u.territory && (
                    <p className="flex items-center gap-1 truncate">
                      <MapPin size={11} className="shrink-0" /> {u.territory}
                    </p>
                  )}
                  {manager && <p className="truncate">Reports to {manager.name}</p>}
                </div>
              </button>
            );
          })}
        </div>
      )}

      {/* Edit / view drawer */}
      <Drawer
        open={!!selected}
        onClose={close}
        title={selected?.name ?? ""}
        subtitle={
          selected && (
            <span className="flex items-center gap-2 flex-wrap">
              {selected.email}
              {!selected.active && <span className="badge-critical">Deactivated</span>}
            </span>
          )
        }
      >
        {selected && edit && (
          <div className="space-y-4">
            <div className="flex items-center gap-3">
              <Avatar name={edit.name || selected.name} color={edit.color} size={52} />
              <div className="min-w-0">
                <p className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                  {roleLabel(selected.role)}
                </p>
                <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                  {selected.lastLoginAt ? `Last signed in ${formatRelative(selected.lastLoginAt)}` : "Hasn't signed in yet"}
                </p>
              </div>
            </div>

            <div className="space-y-4">
              <SectionTitle>Profile</SectionTitle>
              <FieldGrid>
                <Field label="Name">
                  <input className="input" disabled={!canEditSelected} value={edit.name} onChange={(e) => setEdit({ ...edit, name: e.target.value })} />
                </Field>
                <Field label="Initials">
                  <input
                    className="input"
                    disabled={!canEditSelected}
                    maxLength={4}
                    value={edit.initials}
                    onChange={(e) => setEdit({ ...edit, initials: e.target.value.toUpperCase() })}
                  />
                </Field>
                <Field label="Avatar color">
                  <input className="input h-10 p-1" type="color" disabled={!canEditSelected} value={edit.color} onChange={(e) => setEdit({ ...edit, color: e.target.value })} />
                </Field>
              </FieldGrid>
            </div>

            <div className="space-y-4">
              <SectionTitle>Access</SectionTitle>
              {!isAdmin && (
                <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                  Only a Super Admin can change roles, territories and reporting lines.
                </p>
              )}
              <FieldGrid>
                <Field label="Role" hint={isAdmin && isSelf ? "You can't change your own role." : undefined}>
                  <select
                    className="select"
                    disabled={!isAdmin || isSelf}
                    value={edit.role}
                    onChange={(e) => setEdit({ ...edit, role: e.target.value as Role })}
                  >
                    {ROLES.map((r) => (
                      <option key={r} value={r}>
                        {roleLabel(r)}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Territory">
                  <input className="input" disabled={!isAdmin} value={edit.territory} onChange={(e) => setEdit({ ...edit, territory: e.target.value })} />
                </Field>
                <Field label="Reports to" className="sm:col-span-2">
                  <div className="flex gap-2">
                    <select
                      className="select"
                      disabled={!isAdmin}
                      value={edit.reportsToId ?? ""}
                      onChange={(e) => setEdit({ ...edit, reportsToId: e.target.value ? Number(e.target.value) : null })}
                    >
                      <option value="">— None —</option>
                      {users
                        .filter((u) => u.id !== selected.id && (u.active || u.id === edit.reportsToId))
                        .map((u) => (
                          <option key={u.id} value={u.id}>
                            {u.name} · {roleLabel(u.role)}
                          </option>
                        ))}
                    </select>
                    {isAdmin && selected.reportsToId && (
                      <button className="btn-secondary shrink-0" onClick={() => void onClearReportsTo(selected)} disabled={clearReportsTo.isPending}>
                        Clear
                      </button>
                    )}
                  </div>
                </Field>
              </FieldGrid>
            </div>

            {(isAdmin || isSelf) && (
              <div className="space-y-3">
                <SectionTitle>Security</SectionTitle>
                <div className="flex flex-wrap gap-2">
                  {isSelf ? (
                    <button className="btn-secondary" onClick={() => navigate("/change-password")}>
                      <KeyRound size={14} /> Change my password
                    </button>
                  ) : (
                    isAdmin && (
                      <button className="btn-secondary" onClick={() => setResetOpen(true)}>
                        <KeyRound size={14} /> Reset password
                      </button>
                    )
                  )}
                  {isAdmin &&
                    !isSelf &&
                    (selected.active ? (
                      <button
                        className="btn-secondary"
                        style={{ color: "var(--critical)" }}
                        onClick={() => void onDeactivate(selected)}
                        disabled={setActive.isPending}
                      >
                        <UserX size={14} /> Deactivate
                      </button>
                    ) : (
                      <button className="btn-secondary" onClick={() => void onReactivate(selected)} disabled={setActive.isPending}>
                        <UserCheck size={14} /> Reactivate
                      </button>
                    ))}
                </div>
              </div>
            )}

            {canEditSelected && (
              <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
                <button className="btn-ghost" onClick={close}>
                  Cancel
                </button>
                <button className="btn-primary" disabled={!edit.name.trim() || updateUser.isPending} onClick={() => updateUser.mutate()}>
                  {updateUser.isPending ? "Saving…" : "Save changes"}
                </button>
              </div>
            )}
          </div>
        )}
      </Drawer>

      {/* Create drawer */}
      <Drawer open={creating} onClose={() => setCreating(false)} title="Add team member" subtitle="They'll pick their own password at first sign-in.">
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (createValid && !createUser.isPending) createUser.mutate();
          }}
        >
          <FieldGrid>
            <Field label="Name" required>
              <input className="input" autoFocus value={create.name} onChange={(e) => setCreate((d) => ({ ...d, name: e.target.value }))} />
            </Field>
            <Field label="Email" required>
              <input className="input" type="email" value={create.email} onChange={(e) => setCreate((d) => ({ ...d, email: e.target.value }))} />
            </Field>
            <Field label="Role">
              <select className="select" value={create.role} onChange={(e) => setCreate((d) => ({ ...d, role: e.target.value as Role }))}>
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {roleLabel(r)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Territory">
              <input className="input" value={create.territory} onChange={(e) => setCreate((d) => ({ ...d, territory: e.target.value }))} />
            </Field>
            <Field label="Reports to" className="sm:col-span-2">
              <select
                className="select"
                value={create.reportsToId ?? ""}
                onChange={(e) => setCreate((d) => ({ ...d, reportsToId: e.target.value ? Number(e.target.value) : null }))}
              >
                <option value="">— None —</option>
                {users
                  .filter((u) => u.active)
                  .map((u) => (
                    <option key={u.id} value={u.id}>
                      {u.name} · {roleLabel(u.role)}
                    </option>
                  ))}
              </select>
            </Field>
            <Field label="Temporary password" required className="sm:col-span-2">
              <input
                className="input"
                type="password"
                autoComplete="new-password"
                value={create.password}
                onChange={(e) => setCreate((d) => ({ ...d, password: e.target.value }))}
              />
            </Field>
          </FieldGrid>
          <PasswordRules checks={createChecks} />
          <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
            <button type="button" className="btn-ghost" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={!createValid || createUser.isPending}>
              <Plus size={14} /> {createUser.isPending ? "Adding…" : "Add member"}
            </button>
          </div>
        </form>
      </Drawer>

      <Modal
        open={resetOpen}
        onClose={() => setResetOpen(false)}
        title={selected ? `Reset password for ${selected.name}` : "Reset password"}
        width="26rem"
        footer={
          <>
            <button className="btn-ghost" onClick={() => setResetOpen(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!pwChecks.every((c) => c.ok) || resetPassword.isPending} onClick={() => void onResetPassword()}>
              Reset password
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <Field label="Temporary password" hint="Share it securely — they'll be asked to replace it.">
            <input className="input" type="password" autoComplete="new-password" autoFocus value={newPassword} onChange={(e) => setNewPassword(e.target.value)} />
          </Field>
          <PasswordRules checks={pwChecks} />
        </div>
      </Modal>
      {confirmNode}
      {reasonNode}
    </div>
  );
}
