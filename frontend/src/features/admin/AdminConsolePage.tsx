import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  AlertTriangle,
  Bot,
  Building2,
  Copy,
  Cpu,
  Database,
  Download,
  GitMerge,
  HardDrive,
  History,
  KeyRound,
  Lock,
  LogOut,
  Network,
  Plug,
  Search,
  Settings2,
  ShieldAlert,
  ShieldCheck,
  Shuffle,
  SlidersHorizontal,
  Tags,
  Trash2,
  Unlock,
  UserCheck,
  UserPlus,
  Users,
  Users2,
  UserX,
  Webhook,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, Navigate, useSearchParams } from "react-router-dom";
import { api, downloadFile } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { formatDateTime, formatRelative, roleLabel } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Role } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SkeletonRows } from "@/components/ui/Skeleton";
import { StatTile } from "@/components/ui/StatTile";
import { Tabs } from "@/components/ui/Tabs";
import { passwordChecks, PasswordRules } from "@/features/auth/passwordRules";

// ── API shapes (feature-local) ────────────────────────────────────────────────
interface Overview {
  users: {
    total: number;
    active: number;
    deactivated: number;
    lockedNow: number;
    pendingPasswordChange: number;
    neverSignedIn: number;
    activeLast7Days: number;
    byRole: Record<string, number>;
  };
  records: Record<string, number>;
  storage: { attachments: number; bytes: number; backend: string };
  integrations: { apiKeysActive: number; webhooksActive: number; webhooksFailing: number; aiConfigured: boolean };
  securityLast7Days: Record<string, number>;
  activityLast7Days: { date: string; events: number }[];
}

interface AdminUser {
  id: number;
  name: string;
  email: string;
  role: Role;
  active: boolean;
  initials: string | null;
  color: string | null;
  lastLoginAt: string | null;
  createdAt: string | null;
  mustChangePassword: boolean;
  locked: boolean;
  lockedUntil: string | null;
  failedLoginCount: number;
}

interface SecurityEvent {
  id: number;
  action: string;
  resource: string;
  resourceName: string | null;
  userName: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
}

interface SystemInfo {
  version: string;
  profiles: string[];
  schemaVersion?: string;
  schemaDescription?: string;
  pendingMigrations?: number;
  java: string;
  uptimeSeconds: number;
  memory: { usedMb: number; maxMb: number };
  features: Record<string, string | boolean | number | null>;
}

const ROLES: Role[] = ["SUPER_ADMIN", "VP_SALES", "MANAGER", "EXECUTIVE", "INSIDE_SALES", "FIELD_SALES", "SUPPORT"];
type TabKey = "overview" | "users" | "security" | "configuration" | "system";

const EVENT_META: Record<string, { label: string; tone: "good" | "warning" | "critical" | "neutral" | "brand" }> = {
  login: { label: "Signed in", tone: "good" },
  login_failed: { label: "Failed sign-in", tone: "warning" },
  account_locked: { label: "Account locked", tone: "critical" },
  account_unlocked: { label: "Account unlocked", tone: "brand" },
  password_changed: { label: "Password changed", tone: "brand" },
  logout_all: { label: "Signed out everywhere", tone: "neutral" },
  force_logout: { label: "Forced sign-out", tone: "warning" },
  permission_denied: { label: "Permission denied", tone: "critical" },
  export: { label: "Data export", tone: "warning" },
  duplicate_bypass: { label: "Duplicate saved anyway", tone: "neutral" },
  merge: { label: "Records merged", tone: "neutral" },
};

const toneClass = (t: string) =>
  t === "good" ? "badge-good" : t === "warning" ? "badge-warning" : t === "critical" ? "badge-critical" : t === "brand" ? "badge-brand" : "badge-neutral";

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  return `${(n / 1024 ** 3).toFixed(2)} GB`;
}

function formatIp(ip: string | null): string {
  if (!ip) return "—";
  if (ip === "0:0:0:0:0:0:0:1" || ip === "::1" || ip === "127.0.0.1") return `${ip === "127.0.0.1" ? ip : "::1"} (local)`;
  return ip;
}

function formatUptime(s: number): string {
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  return d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h ${m}m` : `${m}m`;
}

export default function AdminConsolePage() {
  const role = useAuthStore((s) => s.user?.role);
  const [params, setParams] = useSearchParams();
  const tab = (params.get("tab") as TabKey) || "overview";
  const setTab = (t: TabKey) => setParams(t === "overview" ? {} : { tab: t }, { replace: true });

  if (role !== "SUPER_ADMIN") return <Navigate to="/" replace />;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Admin console"
        description="Users, security, configuration and system health for your organization."
        icon={<ShieldCheck size={20} />}
        actions={
          <Link to="/audit-log" className="btn-secondary">
            <History size={15} /> Full audit log
          </Link>
        }
      />
      <div className="overflow-x-auto thin-scroll -mx-1 px-1">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: "overview", label: "Overview" },
            { value: "users", label: "Users" },
            { value: "security", label: "Security" },
            { value: "configuration", label: "Configuration" },
            { value: "system", label: "System" },
          ]}
        />
      </div>
      {tab === "overview" && <OverviewTab onOpen={setTab} />}
      {tab === "users" && <UsersTab />}
      {tab === "security" && <SecurityTab />}
      {tab === "configuration" && <ConfigurationTab />}
      {tab === "system" && <SystemTab />}
    </div>
  );
}

// ── Overview ─────────────────────────────────────────────────────────────────
function OverviewTab({ onOpen }: { onOpen: (t: TabKey) => void }) {
  const q = useQuery({ queryKey: ["admin", "overview"], queryFn: async () => (await api.get<Overview>("/admin/overview")).data });
  if (q.isError) return <div className="glass-card"><QueryError error={q.error} onRetry={() => q.refetch()} /></div>;
  if (q.isLoading || !q.data) return <div className="glass-card p-5"><SkeletonRows rows={6} /></div>;
  const o = q.data;
  const sec = o.securityLast7Days;
  // Always seven day-slots (oldest → today), so a single busy day doesn't fill the chart.
  const byDate = new Map(o.activityLast7Days.map((d) => [d.date, d.events]));
  const days = Array.from({ length: 7 }, (_, i) => {
    const dt = new Date();
    dt.setDate(dt.getDate() - (6 - i));
    const key = `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, "0")}-${String(dt.getDate()).padStart(2, "0")}`;
    return { date: key, label: dt.toLocaleDateString(undefined, { weekday: "short" }), events: byDate.get(key) ?? 0 };
  });
  const maxEvents = Math.max(1, ...days.map((d) => d.events));
  const attention: { icon: LucideIcon; text: string; tab: TabKey; tone: "critical" | "warning" }[] = [];
  if (o.users.lockedNow > 0) attention.push({ icon: Lock, text: `${o.users.lockedNow} account${o.users.lockedNow > 1 ? "s are" : " is"} locked after failed sign-ins`, tab: "users", tone: "critical" });
  if (o.integrations.webhooksFailing > 0) attention.push({ icon: Webhook, text: `${o.integrations.webhooksFailing} webhook${o.integrations.webhooksFailing > 1 ? "s are" : " is"} failing deliveries`, tab: "configuration", tone: "warning" });
  if ((sec.permission_denied ?? 0) >= 10) attention.push({ icon: ShieldAlert, text: `${sec.permission_denied} permission denials this week — review for misuse`, tab: "security", tone: "warning" });
  if (!o.integrations.aiConfigured) attention.push({ icon: Bot, text: "AI assistant is not configured", tab: "configuration", tone: "warning" });

  return (
    <div className="flex flex-col gap-6">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
        <StatTile label="Active users" value={o.users.active} icon={Users} sub={`${o.users.total} total · ${o.users.activeLast7Days} active this week`} />
        <StatTile label="Locked accounts" value={o.users.lockedNow} icon={Lock} tone={o.users.lockedNow ? "critical" : "good"} sub={`${o.users.pendingPasswordChange} must set a new password`} />
        <StatTile label="Failed sign-ins (7d)" value={sec.login_failed ?? 0} icon={ShieldAlert} tone={(sec.login_failed ?? 0) > 20 ? "warning" : "brand"} sub={`${sec.account_locked ?? 0} lockouts · ${sec.permission_denied ?? 0} denials`} />
        <StatTile label="Storage" value={formatBytes(o.storage.bytes)} icon={HardDrive} sub={`${o.storage.attachments} files · ${o.storage.backend === "azure_blob" ? "Azure Blob" : "local disk"}`} />
      </div>

      {attention.length > 0 && (
        <section className="glass-card p-5">
          <h2 className="text-sm font-semibold mb-3" style={{ color: "var(--ink-primary)" }}>Needs attention</h2>
          <ul className="space-y-2">
            {attention.map((a) => (
              <li key={a.text}>
                <button
                  onClick={() => onOpen(a.tab)}
                  className="w-full flex items-center gap-3 rounded-xl px-3.5 py-2.5 text-sm text-left transition-colors hover:brightness-105"
                  style={{ backgroundColor: a.tone === "critical" ? "rgb(208 59 59 / 0.08)" : "rgb(201 133 0 / 0.1)", color: "var(--ink-secondary)" }}
                >
                  <a.icon size={16} style={{ color: a.tone === "critical" ? "var(--critical)" : "var(--warning)" }} />
                  <span className="flex-1">{a.text}</span>
                  <span className="text-xs font-semibold" style={{ color: "var(--brand-600)" }}>Review →</span>
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <div className="grid lg:grid-cols-3 gap-4">
        <section className="glass-card p-5 lg:col-span-2">
          <h2 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>Activity, last 7 days</h2>
          <p className="text-xs mb-4" style={{ color: "var(--ink-muted)" }}>Audited changes, sign-ins and exports per day</p>
          <div className="flex items-end gap-2 sm:gap-3 h-40" role="img" aria-label="Audit events per day for the last 7 days">
            {days.map((d) => (
              <div key={d.date} className="flex-1 flex flex-col items-center justify-end gap-1.5 min-w-0 h-full" title={`${d.date}: ${d.events} events`}>
                <span className="text-[11px] tabular-nums font-semibold" style={{ color: d.events ? "var(--ink-secondary)" : "var(--ink-muted)" }}>{d.events}</span>
                <div
                  className="w-full max-w-[44px] rounded-t-md"
                  style={{
                    height: `${d.events ? Math.max(6, (d.events / maxEvents) * 104) : 3}px`,
                    backgroundImage: d.events ? "var(--brand-gradient)" : undefined,
                    backgroundColor: d.events ? undefined : "rgb(var(--panel-border) / 0.1)",
                  }}
                />
                <span className="text-[10px] truncate" style={{ color: "var(--ink-muted)" }}>{d.label}</span>
              </div>
            ))}
          </div>
        </section>
        <section className="glass-card p-5">
          <h2 className="text-sm font-semibold mb-3" style={{ color: "var(--ink-primary)" }}>Users by role</h2>
          <ul className="space-y-2">
            {Object.entries(o.users.byRole).map(([r, n]) => (
              <li key={r} className="flex items-center gap-3 text-sm">
                <span className="w-28 shrink-0 truncate" style={{ color: "var(--ink-secondary)" }}>{roleLabel(r)}</span>
                <span className="flex-1 h-2 rounded-full overflow-hidden" style={{ backgroundColor: "rgb(var(--panel-border) / 0.07)" }}>
                  <span className="block h-full rounded-full" style={{ width: `${o.users.active ? (n / o.users.active) * 100 : 0}%`, backgroundImage: "var(--brand-gradient)" }} />
                </span>
                <span className="w-6 text-right tabular-nums font-semibold" style={{ color: "var(--ink-primary)" }}>{n}</span>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <MiniCard icon={Database} title="CRM records" lines={[`${o.records.leads} leads · ${o.records.opportunities} deals`, `${o.records.companies} companies · ${o.records.contacts} contacts`, `${o.records.tasks} tasks · ${o.records.inTrash} in trash`]} />
        <MiniCard icon={Plug} title="Integrations" lines={[`${o.integrations.apiKeysActive} active API keys`, `${o.integrations.webhooksActive} active webhooks`, o.integrations.webhooksFailing ? `${o.integrations.webhooksFailing} failing` : "All deliveries healthy"]} />
        <MiniCard icon={UserCheck} title="Onboarding" lines={[`${o.users.neverSignedIn} never signed in`, `${o.users.pendingPasswordChange} pending password change`, `${o.users.deactivated} deactivated`]} />
        <MiniCard icon={Download} title="Data access (7d)" lines={[`${sec.export ?? 0} exports`, `${sec.merge ?? 0} merges`, `${sec.duplicate_bypass ?? 0} duplicates saved anyway`]} />
      </div>
    </div>
  );
}

function MiniCard({ icon: Icon, title, lines }: { icon: LucideIcon; title: string; lines: string[] }) {
  return (
    <section className="glass-card p-5">
      <div className="flex items-center gap-2 mb-3">
        <span className="h-8 w-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: "rgb(var(--ring-brand) / 0.12)", color: "var(--brand-600)" }}>
          <Icon size={15} />
        </span>
        <h3 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>{title}</h3>
      </div>
      <ul className="space-y-1 text-sm" style={{ color: "var(--ink-secondary)" }}>
        {lines.map((l) => <li key={l}>{l}</li>)}
      </ul>
    </section>
  );
}

// ── Users ────────────────────────────────────────────────────────────────────
type UserFilter = "all" | "active" | "locked" | "pending" | "deactivated";

function UsersTab() {
  const me = useAuthStore((s) => s.user);
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<UserFilter>("all");
  const [createOpen, setCreateOpen] = useState(false);
  const [resetFor, setResetFor] = useState<AdminUser | null>(null);
  const q = useQuery({ queryKey: ["admin", "users"], queryFn: async () => (await api.get<AdminUser[]>("/admin/users")).data });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["admin"] });
    qc.invalidateQueries({ queryKey: ["users"] });
  };

  const update = useMutation({
    mutationFn: async ({ id, patch }: { id: number; patch: Record<string, unknown> }) => api.put(`/users/${id}`, patch),
    onSuccess: () => {
      toast.success("User updated");
      refresh();
    },
    onError: (e: Error) => toast.error(e.message),
  });
  const unlock = useMutation({
    mutationFn: async (id: number) => api.post(`/admin/users/${id}/unlock`),
    onSuccess: () => {
      toast.success("Account unlocked");
      refresh();
    },
    onError: (e: Error) => toast.error(e.message),
  });
  const forceLogout = useMutation({
    mutationFn: async (id: number) => api.post(`/admin/users/${id}/force-logout`),
    onSuccess: () => toast.success("Signed out on every device"),
    onError: (e: Error) => toast.error(e.message),
  });

  const rows = useMemo(() => {
    const t = search.trim().toLowerCase();
    return (q.data ?? []).filter((u) => {
      if (t && !u.name.toLowerCase().includes(t) && !u.email.toLowerCase().includes(t)) return false;
      if (filter === "active") return u.active;
      if (filter === "locked") return u.locked;
      if (filter === "pending") return u.mustChangePassword;
      if (filter === "deactivated") return !u.active;
      return true;
    });
  }, [q.data, search, filter]);
  const counts = useMemo(() => {
    const d = q.data ?? [];
    return { all: d.length, active: d.filter((u) => u.active).length, locked: d.filter((u) => u.locked).length, pending: d.filter((u) => u.mustChangePassword).length, deactivated: d.filter((u) => !u.active).length };
  }, [q.data]);

  return (
    <section className="glass-card overflow-hidden">
      <div className="flex flex-col lg:flex-row lg:items-center gap-3 px-5 py-4 hairline border-b">
        <div className="flex flex-wrap gap-1.5">
          {(["all", "active", "locked", "pending", "deactivated"] as UserFilter[]).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className="rounded-full px-3 py-1 text-xs font-medium transition-colors"
              style={{
                backgroundColor: filter === f ? "rgb(var(--ring-brand) / 0.14)" : "rgb(var(--panel-border) / 0.06)",
                color: filter === f ? "var(--brand-600)" : "var(--ink-secondary)",
              }}
              aria-pressed={filter === f}
            >
              {f === "pending" ? "Password pending" : f[0].toUpperCase() + f.slice(1)} · {counts[f]}
            </button>
          ))}
        </div>
        <div className="flex gap-2 lg:ml-auto">
          <div className="relative flex-1 lg:w-64">
            <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" style={{ color: "var(--ink-muted)" }} />
            <input className="input pl-9" placeholder="Search name or email…" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
          <button className="btn-primary shrink-0" onClick={() => setCreateOpen(true)}>
            <UserPlus size={15} /> Add user
          </button>
        </div>
      </div>

      {q.isError ? (
        <QueryError error={q.error} onRetry={() => q.refetch()} />
      ) : q.isLoading ? (
        <div className="p-5"><SkeletonRows rows={6} /></div>
      ) : rows.length === 0 ? (
        <EmptyState icon={Users} title="No users match" description="Try another filter or search term." />
      ) : (
        <>
        {/* Phones: one card per user */}
        <ul className="md:hidden divide-y" style={{ borderColor: "rgb(var(--panel-border) / 0.06)" }}>
          {rows.map((u) => {
            const self = u.id === me?.id;
            return (
              <li key={u.id} className="px-4 py-4 space-y-3">
                <div className="flex items-center gap-3 min-w-0">
                  <Avatar name={u.name} color={u.color} size={36} />
                  <div className="min-w-0 flex-1">
                    <div className="font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                      {u.name} {self && <span className="badge-brand ml-1">You</span>}
                    </div>
                    <div className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>{u.email}</div>
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-1.5 text-xs" style={{ color: "var(--ink-muted)" }}>
                  <span className="badge-neutral">{roleLabel(u.role)}</span>
                  {u.active ? <span className="badge-good">Active</span> : <span className="badge-neutral">Deactivated</span>}
                  {u.locked && <span className="badge-critical">Locked</span>}
                  {u.mustChangePassword && <span className="badge-warning">Password pending</span>}
                  <span className="ml-auto">{u.lastLoginAt ? formatRelative(u.lastLoginAt) : "Never signed in"}</span>
                </div>
                <div className="flex flex-wrap gap-2">
                  {u.locked && (
                    <button className="btn-secondary py-1.5" onClick={() => unlock.mutate(u.id)}>
                      <Unlock size={14} /> Unlock
                    </button>
                  )}
                  {!self && (
                    <button className="btn-secondary py-1.5" onClick={() => setResetFor(u)}>
                      <KeyRound size={14} /> Reset password
                    </button>
                  )}
                  <button
                    className="btn-secondary py-1.5"
                    onClick={async () => {
                      if (await confirm(`Sign ${u.name} out everywhere?`, "Every session on every device ends now.", false)) forceLogout.mutate(u.id);
                    }}
                  >
                    <LogOut size={14} /> Sign out
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
        <div className="hidden md:block overflow-x-auto thin-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>User</th>
                <th>Role</th>
                <th>Status</th>
                <th>Last sign-in</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((u) => {
                const self = u.id === me?.id;
                return (
                  <tr key={u.id} className="cursor-default">
                    <td>
                      <div className="flex items-center gap-3 min-w-0">
                        <Avatar name={u.name} color={u.color} size={32} />
                        <div className="min-w-0">
                          <div className="font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                            {u.name} {self && <span className="badge-brand ml-1">You</span>}
                          </div>
                          <div className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>{u.email}</div>
                        </div>
                      </div>
                    </td>
                    <td>
                      <select
                        className="select py-1.5 w-40"
                        value={u.role}
                        disabled={self || update.isPending}
                        title={self ? "You can't change your own role" : undefined}
                        aria-label={`Role for ${u.name}`}
                        onChange={async (e) => {
                          const next = e.target.value as Role;
                          if (await confirm(`Make ${u.name} ${roleLabel(next)}?`, "Their permissions change immediately and their current sessions are signed out.", false)) {
                            update.mutate({ id: u.id, patch: { role: next } });
                          }
                        }}
                      >
                        {ROLES.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
                      </select>
                    </td>
                    <td>
                      <div className="flex flex-wrap gap-1">
                        {u.active ? <span className="badge-good">Active</span> : <span className="badge-neutral">Deactivated</span>}
                        {u.locked && <span className="badge-critical" title={u.lockedUntil ? `Until ${formatDateTime(u.lockedUntil)}` : undefined}>Locked</span>}
                        {u.mustChangePassword && <span className="badge-warning">Password pending</span>}
                      </div>
                    </td>
                    <td title={u.lastLoginAt ? formatDateTime(u.lastLoginAt) : undefined}>
                      <span className="text-sm" style={{ color: "var(--ink-secondary)" }}>{u.lastLoginAt ? formatRelative(u.lastLoginAt) : "Never"}</span>
                    </td>
                    <td className="text-right whitespace-nowrap">
                      {u.locked && (
                        <button className="btn-icon" title="Unlock account" aria-label={`Unlock ${u.name}`} onClick={() => unlock.mutate(u.id)}>
                          <Unlock size={15} />
                        </button>
                      )}
                      {!self && (
                        <button className="btn-icon" title="Reset password" aria-label={`Reset password for ${u.name}`} onClick={() => setResetFor(u)}>
                          <KeyRound size={15} />
                        </button>
                      )}
                      <button
                        className="btn-icon"
                        title="Sign out on all devices"
                        aria-label={`Sign out ${u.name} everywhere`}
                        onClick={async () => {
                          if (await confirm(`Sign ${u.name} out everywhere?`, "Every session on every device ends now. They can sign in again with their password.", false)) forceLogout.mutate(u.id);
                        }}
                      >
                        <LogOut size={15} />
                      </button>
                      {!self && (
                        <button
                          className="btn-icon"
                          title={u.active ? "Deactivate" : "Reactivate"}
                          aria-label={`${u.active ? "Deactivate" : "Reactivate"} ${u.name}`}
                          style={{ color: u.active ? "var(--critical)" : "var(--good)" }}
                          onClick={async () => {
                            if (!u.active) return update.mutate({ id: u.id, patch: { active: true } });
                            if (await confirm(`Deactivate ${u.name}?`, "They are signed out immediately and can't sign in. Their records stay; you can reactivate them later."))
                              update.mutate({ id: u.id, patch: { active: false } });
                          }}
                        >
                          {u.active ? <UserX size={15} /> : <UserCheck size={15} />}
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        </>
      )}
      <CreateUserModal open={createOpen} onClose={() => setCreateOpen(false)} onCreated={refresh} />
      <ResetPasswordModal user={resetFor} onClose={() => setResetFor(null)} onDone={refresh} />
      {confirmNode}
    </section>
  );
}

function CreateUserModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: () => void }) {
  const [f, setF] = useState({ name: "", email: "", role: "EXECUTIVE" as Role, password: "", territory: "" });
  const checks = passwordChecks(f.password, f.email);
  const create = useMutation({
    mutationFn: async () =>
      api.post("/users", { name: f.name.trim(), email: f.email.trim(), role: f.role, password: f.password, territory: f.territory.trim() || undefined }),
    onSuccess: () => {
      toast.success("User created — they'll set their own password at first sign-in");
      setF({ name: "", email: "", role: "EXECUTIVE", password: "", territory: "" });
      onCreated();
      onClose();
    },
    onError: (e: Error) => toast.error(e.message),
  });
  const valid = f.name.trim() && /^\S+@\S+\.\S+$/.test(f.email.trim()) && checks.every((c) => c.ok);
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Add user"
      footer={
        <>
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!valid || create.isPending} onClick={() => create.mutate()}>Create user</button>
        </>
      }
    >
      <div className="space-y-4">
        <FieldGrid>
          <Field label="Full name" required>
            <input className="input" value={f.name} onChange={(e) => setF((s) => ({ ...s, name: e.target.value }))} autoFocus />
          </Field>
          <Field label="Email" required>
            <input className="input" type="email" value={f.email} onChange={(e) => setF((s) => ({ ...s, email: e.target.value }))} />
          </Field>
          <Field label="Role">
            <select className="select" value={f.role} onChange={(e) => setF((s) => ({ ...s, role: e.target.value as Role }))}>
              {ROLES.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
            </select>
          </Field>
          <Field label="Territory" hint="Used for Field Sales lead routing">
            <input className="input" value={f.territory} onChange={(e) => setF((s) => ({ ...s, territory: e.target.value }))} />
          </Field>
        </FieldGrid>
        <Field label="Temporary password" required hint="Share it securely — they must replace it at first sign-in.">
          <input className="input" type="password" autoComplete="new-password" value={f.password} onChange={(e) => setF((s) => ({ ...s, password: e.target.value }))} />
        </Field>
        <PasswordRules checks={checks} />
      </div>
    </Modal>
  );
}

function ResetPasswordModal({ user, onClose, onDone }: { user: AdminUser | null; onClose: () => void; onDone: () => void }) {
  const [pw, setPw] = useState("");
  const checks = passwordChecks(pw, user?.email);
  const reset = useMutation({
    mutationFn: async () => api.post(`/users/${user!.id}/reset-password`, { newPassword: pw }),
    onSuccess: () => {
      toast.success("Password reset — they'll choose a new one at next sign-in");
      setPw("");
      onDone();
      onClose();
    },
    onError: (e: Error) => toast.error(e.message),
  });
  return (
    <Modal
      open={!!user}
      onClose={onClose}
      title={`Reset password for ${user?.name ?? ""}`}
      footer={
        <>
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!checks.every((c) => c.ok) || reset.isPending} onClick={() => reset.mutate()}>Reset password</button>
        </>
      }
    >
      <div className="space-y-3">
        <p className="text-sm" style={{ color: "var(--ink-secondary)" }}>
          All of their sessions end now, and they must set a new password the next time they sign in.
        </p>
        <Field label="Temporary password">
          <input className="input" type="password" autoComplete="new-password" value={pw} onChange={(e) => setPw(e.target.value)} autoFocus />
        </Field>
        <PasswordRules checks={checks} />
      </div>
    </Modal>
  );
}

// ── Security ─────────────────────────────────────────────────────────────────
function SecurityTab() {
  const [action, setAction] = useState("");
  const [days, setDays] = useState(30);
  const q = useQuery({
    queryKey: ["admin", "security", action, days],
    queryFn: async () =>
      (await api.get<{ data: SecurityEvent[]; actions: string[] }>("/admin/security-events", { params: { days, action: action || undefined, limit: 200 } })).data,
  });
  return (
    <section className="glass-card overflow-hidden">
      <div className="flex flex-col sm:flex-row sm:items-center gap-3 px-5 py-4 hairline border-b">
        <select className="select sm:w-56" value={action} onChange={(e) => setAction(e.target.value)} aria-label="Event type">
          <option value="">All security events</option>
          {Object.entries(EVENT_META).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
        </select>
        <select className="select sm:w-40" value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label="Period">
          <option value={1}>Last 24 hours</option>
          <option value={7}>Last 7 days</option>
          <option value={30}>Last 30 days</option>
          <option value={90}>Last 90 days</option>
        </select>
        <button className="btn-secondary sm:ml-auto" onClick={() => downloadFile("/audit-events/export", "audit-log.csv").catch((e: Error) => toast.error(e.message))}>
          <Download size={15} /> Export audit log
        </button>
      </div>
      {q.isError ? (
        <QueryError error={q.error} onRetry={() => q.refetch()} />
      ) : q.isLoading ? (
        <div className="p-5"><SkeletonRows rows={8} /></div>
      ) : !q.data?.data.length ? (
        <EmptyState icon={ShieldCheck} title="No security events" description="Nothing matched this period and filter." />
      ) : (
        <div className="overflow-x-auto thin-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>When</th>
                <th>Event</th>
                <th>User</th>
                <th>Detail</th>
                <th>IP address</th>
              </tr>
            </thead>
            <tbody>
              {q.data.data.map((e) => {
                const meta = EVENT_META[e.action] ?? { label: e.action, tone: "neutral" as const };
                return (
                  <tr key={e.id} className="cursor-default">
                    <td className="whitespace-nowrap" title={formatDateTime(e.createdAt)}>{formatRelative(e.createdAt)}</td>
                    <td><span className={toneClass(meta.tone)}>{meta.label}</span></td>
                    <td>{e.userName ?? "—"}</td>
                    <td className="max-w-xs truncate" title={e.resourceName ?? undefined} style={{ color: "var(--ink-secondary)" }}>
                      {e.resourceName ?? e.resource}
                    </td>
                    <td className="font-mono text-xs whitespace-nowrap">{formatIp(e.ipAddress)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// ── Configuration hub ────────────────────────────────────────────────────────
const CONFIG: { group: string; items: { icon: LucideIcon; title: string; desc: string; to: string }[] }[] = [
  {
    group: "Organization",
    items: [
      { icon: Building2, title: "Organization profile", desc: "Name and industry", to: "/settings?tab=organization" },
      { icon: Bot, title: "AI assistant", desc: "Provider, model and API key", to: "/settings?tab=ai" },
      { icon: Users2, title: "Teams", desc: "Rosters and leaders that drive team-scope access", to: "/teams" },
      { icon: Network, title: "Departments", desc: "Org structure", to: "/departments" },
    ],
  },
  {
    group: "Sales process",
    items: [
      { icon: SlidersHorizontal, title: "Pipeline settings", desc: "Stage probabilities, forecast categories, big-deal alerts", to: "/opportunities" },
      { icon: Shuffle, title: "Lead routing", desc: "Round-robin assignment and territories", to: "/leads" },
      { icon: Copy, title: "Duplicate rules", desc: "Warn or block on matching records", to: "/duplicates" },
      { icon: GitMerge, title: "Merge duplicates", desc: "Review and merge matching records", to: "/duplicates" },
      { icon: Tags, title: "Contact categories", desc: "Segmentation for audiences", to: "/contacts" },
    ],
  },
  {
    group: "Integrations & data",
    items: [
      { icon: Plug, title: "API keys & webhooks", desc: "Master API access and signed event delivery", to: "/integrations" },
      { icon: Activity, title: "Automations", desc: "Triggers, actions and run history", to: "/automations" },
      { icon: History, title: "Audit log", desc: "Every change, filterable and exportable", to: "/audit-log" },
      { icon: Trash2, title: "Trash", desc: "Restore soft-deleted records", to: "/trash" },
    ],
  },
];

function ConfigurationTab() {
  return (
    <div className="flex flex-col gap-6">
      {CONFIG.map((g) => (
        <section key={g.group}>
          <h2 className="text-xs font-bold uppercase tracking-wider mb-3" style={{ color: "var(--ink-muted)" }}>{g.group}</h2>
          <div className="grid sm:grid-cols-2 xl:grid-cols-4 gap-3">
            {g.items.map((i) => (
              <Link key={i.title} to={i.to} className="glass-card-hover p-4 flex items-start gap-3 rounded-2xl">
                <span className="h-9 w-9 shrink-0 rounded-xl flex items-center justify-center" style={{ backgroundColor: "rgb(var(--ring-brand) / 0.12)", color: "var(--brand-600)" }}>
                  <i.icon size={16} />
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>{i.title}</span>
                  <span className="block text-xs mt-0.5" style={{ color: "var(--ink-muted)" }}>{i.desc}</span>
                </span>
              </Link>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

// ── System ───────────────────────────────────────────────────────────────────
const FEATURE_LABELS: Record<string, string> = {
  fileStorage: "File storage",
  webPush: "Web push",
  rateLimiting: "Rate limiting",
  applicationInsights: "Application Insights",
  whatsappSignedWebhooks: "WhatsApp signed webhooks",
  apiDocs: "Public API reference",
  smsAzureCommunication: "SMS (Azure Communication Services)",
  emailInboxPolling: "Inbox reply watching",
};

function SystemTab() {
  const q = useQuery({ queryKey: ["admin", "system"], queryFn: async () => (await api.get<SystemInfo>("/admin/system")).data });
  if (q.isError) return <div className="glass-card"><QueryError error={q.error} onRetry={() => q.refetch()} /></div>;
  if (q.isLoading || !q.data) return <div className="glass-card p-5"><SkeletonRows rows={6} /></div>;
  const s = q.data;
  const rows: [string, React.ReactNode][] = [
    ["Version", s.version],
    ["Profile", s.profiles.join(", ")],
    ["Database schema", s.schemaVersion ? `v${s.schemaVersion} — ${s.schemaDescription}` : "—"],
    ["Pending migrations", s.pendingMigrations ?? "—"],
    ["Java runtime", s.java],
    ["Uptime", formatUptime(s.uptimeSeconds)],
    ["Memory", `${s.memory.usedMb} MB of ${s.memory.maxMb} MB`],
  ];
  return (
    <div className="grid lg:grid-cols-2 gap-4">
      <section className="glass-card p-5">
        <div className="flex items-center gap-2 mb-4">
          <Cpu size={16} style={{ color: "var(--brand-600)" }} />
          <h2 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>Runtime</h2>
        </div>
        <dl className="divide-y" style={{ borderColor: "rgb(var(--panel-border) / 0.06)" }}>
          {rows.map(([k, v]) => (
            <div key={k} className="flex justify-between gap-4 py-2.5 text-sm">
              <dt style={{ color: "var(--ink-muted)" }}>{k}</dt>
              <dd className="text-right font-medium" style={{ color: "var(--ink-primary)" }}>{v}</dd>
            </div>
          ))}
        </dl>
      </section>
      <section className="glass-card p-5">
        <div className="flex items-center gap-2 mb-4">
          <Settings2 size={16} style={{ color: "var(--brand-600)" }} />
          <h2 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>Integrations & services</h2>
        </div>
        <ul className="divide-y" style={{ borderColor: "rgb(var(--panel-border) / 0.06)" }}>
          {Object.entries(s.features).map(([k, v]) => {
            const on = v !== null && v !== false && v !== 0;
            return (
              <li key={k} className="flex items-center justify-between gap-4 py-2.5 text-sm">
                <span style={{ color: "var(--ink-secondary)" }}>{FEATURE_LABELS[k] ?? k}</span>
                {on ? (
                  <span className="badge-good">
                    {typeof v === "string" ? v : typeof v === "number" ? `${v} mailbox${v === 1 ? "" : "es"}` : "Enabled"}
                  </span>
                ) : (
                  <span className="badge-neutral">Not configured</span>
                )}
              </li>
            );
          })}
        </ul>
        <p className="text-xs mt-4 flex items-start gap-1.5" style={{ color: "var(--ink-muted)" }}>
          <AlertTriangle size={12} className="mt-0.5 shrink-0" />
          Services are enabled with environment variables — see DEPLOYMENT.md. Secrets are never shown here.
        </p>
      </section>
    </div>
  );
}
