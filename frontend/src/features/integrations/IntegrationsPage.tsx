import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  BookOpen,
  Check,
  Copy,
  KeyRound,
  Plug,
  Plus,
  RefreshCw,
  Send,
  ShieldAlert,
  Trash2,
  Webhook,
} from "lucide-react";
import { useState } from "react";
import { Navigate } from "react-router-dom";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { formatDateTime, formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { SkeletonRows } from "@/components/ui/Skeleton";
import type { User } from "@/lib/types";

interface ApiKeyView {
  id: number;
  name: string;
  prefix: string;
  scope: "read" | "read_write";
  actingUserId: number;
  actingUserName: string | null;
  createdAt: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
  lastUsedIp: string | null;
  requestCount: number;
  status: "active" | "revoked" | "expired";
}

interface WebhookView {
  id: number;
  name: string;
  url: string;
  events: string[];
  active: boolean;
  consecutiveFailures: number;
  lastDeliveryAt: string | null;
  lastStatusCode: number | null;
  createdAt: string;
}

interface Delivery {
  id: number;
  event: string;
  deliveryId: string;
  attempt: number;
  statusCode: number | null;
  success: boolean;
  durationMs: number | null;
  error: string | null;
  createdAt: string;
}

interface Overview {
  apiKeys: ApiKeyView[];
  webhooks: WebhookView[];
  events: string[];
  masterApiBase: string;
}

function CopyButton({ value, label = "Copy" }: { value: string; label?: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className="btn-secondary shrink-0"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setDone(true);
          setTimeout(() => setDone(false), 1500);
        } catch {
          toast.error("Couldn't access the clipboard — select and copy manually.");
        }
      }}
    >
      {done ? <Check size={14} /> : <Copy size={14} />} {done ? "Copied" : label}
    </button>
  );
}

function SecretReveal({ title, value, onClose }: { title: string; value: string | null; onClose: () => void }) {
  return (
    <Modal open={!!value} onClose={onClose} title={title} footer={<button className="btn-primary" onClick={onClose}>I've stored it safely</button>}>
      <div className="space-y-3">
        <div className="flex items-start gap-2 rounded-lg px-3 py-2.5 text-sm" style={{ backgroundColor: "rgb(201 133 0 / 0.12)", color: "var(--warning)" }}>
          <ShieldAlert size={16} className="shrink-0 mt-0.5" />
          This is the only time it will be shown. Store it in your secrets manager now.
        </div>
        <div className="flex gap-2">
          <input className="input font-mono text-xs" readOnly value={value ?? ""} onFocus={(e) => e.currentTarget.select()} />
          <CopyButton value={value ?? ""} />
        </div>
      </div>
    </Modal>
  );
}

export default function IntegrationsPage() {
  const role = useAuthStore((s) => s.user?.role);
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();
  const [keyModal, setKeyModal] = useState(false);
  const [hookModal, setHookModal] = useState(false);
  const [revealed, setRevealed] = useState<{ title: string; value: string } | null>(null);
  const [deliveriesFor, setDeliveriesFor] = useState<WebhookView | null>(null);

  const overview = useQuery({
    queryKey: ["integrations"],
    queryFn: async () => (await api.get<Overview>("/integrations")).data,
    enabled: role === "SUPER_ADMIN",
  });
  const users = useQuery({
    queryKey: ["users"],
    queryFn: async () => (await api.get<User[]>("/users")).data,
    enabled: role === "SUPER_ADMIN",
  });

  const refresh = () => qc.invalidateQueries({ queryKey: ["integrations"] });

  const revokeKey = useMutation({
    mutationFn: async (id: number) => api.delete(`/integrations/api-keys/${id}`),
    onSuccess: () => {
      toast.success("API key revoked");
      refresh();
    },
    onError: (e: Error) => toast.error(e.message),
  });
  const toggleHook = useMutation({
    mutationFn: async (h: WebhookView) => api.put(`/integrations/webhooks/${h.id}`, { active: !h.active }),
    onSuccess: refresh,
    onError: (e: Error) => toast.error(e.message),
  });
  const testHook = useMutation({
    mutationFn: async (id: number) => (await api.post<Delivery>(`/integrations/webhooks/${id}/test`)).data,
    onSuccess: (d) => {
      if (d.success) toast.success(`Test delivered (HTTP ${d.statusCode}, ${d.durationMs} ms)`);
      else toast.error(`Test failed: ${d.error ?? "HTTP " + d.statusCode}`);
      refresh();
    },
    onError: (e: Error) => toast.error(e.message),
  });
  const rotate = useMutation({
    mutationFn: async (id: number) => (await api.post(`/integrations/webhooks/${id}/rotate-secret`)).data,
    onSuccess: (d) => setRevealed({ title: "New signing secret", value: d.secret }),
    onError: (e: Error) => toast.error(e.message),
  });
  const deleteHook = useMutation({
    mutationFn: async (id: number) => api.delete(`/integrations/webhooks/${id}`),
    onSuccess: () => {
      toast.success("Webhook deleted");
      refresh();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (role !== "SUPER_ADMIN") return <Navigate to="/" replace />;

  const origin = window.location.origin;
  const base = `${origin}/api/master/v1`;
  const data = overview.data;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Integrations"
        description="Connect other platforms through the Master API, API keys and signed webhooks."
        icon={<Plug size={20} />}
        actions={
          <a className="btn-secondary" href="/api/docs" target="_blank" rel="noopener noreferrer">
            <BookOpen size={15} /> API reference
          </a>
        }
      />

      {overview.isError ? (
        <QueryError error={overview.error} onRetry={() => overview.refetch()} />
      ) : (
        <>
          {/* Master API quick start */}
          <section className="glass-card p-5 space-y-4">
            <div className="flex items-center gap-2">
              <Activity size={16} style={{ color: "var(--brand-600)" }} />
              <h2 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>Master API</h2>
              <span className="badge-brand">v1</span>
            </div>
            <p className="text-sm" style={{ color: "var(--ink-secondary)" }}>
              One entry point for every CRM resource. Calls run with the permissions of the key's acting user — the same
              rules, field masking and audit trail as the app itself.
            </p>
            <div className="flex gap-2">
              <input className="input font-mono text-xs" readOnly value={base} />
              <CopyButton value={base} label="Copy URL" />
            </div>
            <pre className="rounded-xl p-4 text-[12px] leading-relaxed overflow-x-auto thin-scroll font-mono" style={{ backgroundColor: "var(--surface-sunken)", color: "var(--ink-secondary)" }}>
{`# Discover every resource and operation
curl -H "X-API-Key: bcrm_…" ${base}

# List leads, create a contact
curl -H "X-API-Key: bcrm_…" "${base}/leads?search=acme"
curl -X POST -H "X-API-Key: bcrm_…" -H "Content-Type: application/json" \\
     -d '{"name":"Ada Lovelace","email":"ada@example.com"}' ${base}/contacts

# Several operations in one call (atomic = all-or-nothing)
curl -X POST -H "X-API-Key: bcrm_…" -H "Content-Type: application/json" ${base}/batch -d '{
  "atomic": true,
  "operations": [
    {"id":"co","method":"POST","path":"/companies","body":{"name":"Acme"}},
    {"id":"ct","method":"POST","path":"/contacts","body":{"name":"Wile E.","companyId":"{{co.body.id}}"}}
  ]}'`}
            </pre>
          </section>

          {/* API keys */}
          <section className="glass-card overflow-hidden">
            <div className="flex items-center justify-between px-5 py-4 hairline border-b">
              <div className="flex items-center gap-2">
                <KeyRound size={16} style={{ color: "var(--brand-600)" }} />
                <h2 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>API keys</h2>
              </div>
              <button className="btn-primary" onClick={() => setKeyModal(true)}>
                <Plus size={15} /> New key
              </button>
            </div>
            {overview.isLoading ? (
              <div className="p-5"><SkeletonRows rows={3} /></div>
            ) : !data?.apiKeys.length ? (
              <EmptyState icon={KeyRound} title="No API keys yet" description="Create a key to let another platform call the Master API." />
            ) : (
              <div className="overflow-x-auto thin-scroll">
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Key</th>
                      <th>Scope</th>
                      <th>Acts as</th>
                      <th>Last used</th>
                      <th className="text-right">Requests</th>
                      <th>Status</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {data.apiKeys.map((k) => (
                      <tr key={k.id} className="cursor-default">
                        <td className="font-medium" style={{ color: "var(--ink-primary)" }}>{k.name}</td>
                        <td className="font-mono text-xs">{k.prefix}…</td>
                        <td>{k.scope === "read" ? <span className="badge-neutral">Read only</span> : <span className="badge-warning">Read & write</span>}</td>
                        <td>{k.actingUserName ?? `#${k.actingUserId}`}</td>
                        <td title={k.lastUsedAt ? formatDateTime(k.lastUsedAt) : undefined}>{k.lastUsedAt ? formatRelative(k.lastUsedAt) : "Never"}</td>
                        <td className="text-right tabular-nums">{k.requestCount.toLocaleString()}</td>
                        <td>
                          {k.status === "active" ? <span className="badge-good">Active</span> : k.status === "expired" ? <span className="badge-warning">Expired</span> : <span className="badge-neutral">Revoked</span>}
                          {k.expiresAt && k.status === "active" && (
                            <span className="block text-[11px] mt-0.5" style={{ color: "var(--ink-muted)" }}>until {formatDateTime(k.expiresAt)}</span>
                          )}
                        </td>
                        <td className="text-right">
                          {k.status === "active" && (
                            <button
                              className="btn-icon"
                              aria-label={`Revoke ${k.name}`}
                              title="Revoke"
                              onClick={async () => {
                                if (await confirm("Revoke API key?", `"${k.name}" stops working immediately. This can't be undone.`)) revokeKey.mutate(k.id);
                              }}
                            >
                              <Trash2 size={15} />
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* Webhooks */}
          <section className="glass-card overflow-hidden">
            <div className="flex items-center justify-between px-5 py-4 hairline border-b">
              <div className="flex items-center gap-2">
                <Webhook size={16} style={{ color: "var(--brand-600)" }} />
                <h2 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>Webhooks</h2>
              </div>
              <button className="btn-primary" onClick={() => setHookModal(true)}>
                <Plus size={15} /> New webhook
              </button>
            </div>
            {overview.isLoading ? (
              <div className="p-5"><SkeletonRows rows={2} /></div>
            ) : !data?.webhooks.length ? (
              <EmptyState icon={Webhook} title="No webhooks yet" description="Get an HMAC-signed POST whenever leads, deals, contacts and more change." />
            ) : (
              <ul className="divide-y" style={{ borderColor: "rgb(var(--panel-border) / 0.06)" }}>
                {data.webhooks.map((h) => (
                  <li key={h.id} className="px-5 py-4 flex flex-col lg:flex-row lg:items-center gap-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="font-medium text-sm" style={{ color: "var(--ink-primary)" }}>{h.name}</span>
                        {h.active ? <span className="badge-good">Active</span> : <span className="badge-neutral">Paused</span>}
                        {h.consecutiveFailures > 0 && <span className="badge-critical">{h.consecutiveFailures} failing</span>}
                      </div>
                      <p className="text-xs font-mono truncate mt-1" style={{ color: "var(--ink-muted)" }}>{h.url}</p>
                      <p className="text-xs mt-1" style={{ color: "var(--ink-muted)" }}>
                        {h.events.join(", ")} · last delivery {h.lastDeliveryAt ? `${formatRelative(h.lastDeliveryAt)} (HTTP ${h.lastStatusCode ?? "—"})` : "never"}
                      </p>
                    </div>
                    <div className="flex items-center gap-1 flex-wrap">
                      <button className="btn-ghost" onClick={() => testHook.mutate(h.id)} disabled={testHook.isPending}>
                        <Send size={14} /> Test
                      </button>
                      <button className="btn-ghost" onClick={() => setDeliveriesFor(h)}>
                        <Activity size={14} /> Deliveries
                      </button>
                      <button className="btn-ghost" onClick={() => toggleHook.mutate(h)}>
                        {h.active ? "Pause" : "Resume"}
                      </button>
                      <button
                        className="btn-ghost"
                        onClick={async () => {
                          if (await confirm("Rotate signing secret?", "The old secret stops verifying immediately — update your receiver right after.", false)) rotate.mutate(h.id);
                        }}
                      >
                        <RefreshCw size={14} /> Rotate secret
                      </button>
                      <button
                        className="btn-icon"
                        aria-label={`Delete ${h.name}`}
                        onClick={async () => {
                          if (await confirm("Delete webhook?", `"${h.name}" and its delivery history will be removed.`)) deleteHook.mutate(h.id);
                        }}
                      >
                        <Trash2 size={15} />
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
            <div className="px-5 py-3 text-xs hairline border-t" style={{ color: "var(--ink-muted)" }}>
              Verify each delivery: <code className="font-mono">X-BetterCRM-Signature = sha256=HMAC_SHA256(secret, X-BetterCRM-Timestamp + "." + body)</code>.
              Failed deliveries retry after 10 s, 1 min and 5 min.
            </div>
          </section>
        </>
      )}

      <CreateKeyModal
        open={keyModal}
        onClose={() => setKeyModal(false)}
        users={users.data ?? []}
        onCreated={(raw) => {
          setKeyModal(false);
          setRevealed({ title: "Your new API key", value: raw });
          refresh();
        }}
      />
      <CreateHookModal
        open={hookModal}
        onClose={() => setHookModal(false)}
        events={data?.events ?? []}
        onCreated={(secret) => {
          setHookModal(false);
          setRevealed({ title: "Webhook signing secret", value: secret });
          refresh();
        }}
      />
      <DeliveriesDrawer hook={deliveriesFor} onClose={() => setDeliveriesFor(null)} />
      <SecretReveal title={revealed?.title ?? ""} value={revealed?.value ?? null} onClose={() => setRevealed(null)} />
      {confirmNode}
    </div>
  );
}

function CreateKeyModal({ open, onClose, users, onCreated }: { open: boolean; onClose: () => void; users: User[]; onCreated: (raw: string) => void }) {
  const me = useAuthStore((s) => s.user);
  const [form, setForm] = useState({ name: "", scope: "read", actingUserId: "", expiresInDays: "" });
  const create = useMutation({
    mutationFn: async () =>
      (
        await api.post("/integrations/api-keys", {
          name: form.name.trim(),
          scope: form.scope,
          actingUserId: form.actingUserId ? Number(form.actingUserId) : undefined,
          expiresInDays: form.expiresInDays ? Number(form.expiresInDays) : undefined,
        })
      ).data,
    onSuccess: (d) => {
      setForm({ name: "", scope: "read", actingUserId: "", expiresInDays: "" });
      onCreated(d.key);
    },
    onError: (e: Error) => toast.error(e.message),
  });
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New API key"
      footer={
        <>
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!form.name.trim() || create.isPending} onClick={() => create.mutate()}>
            Create key
          </button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Name" required hint="Where it's used, e.g. “ERP sync” or “Zapier”.">
          <input className="input" value={form.name} maxLength={150} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} autoFocus />
        </Field>
        <FieldGrid>
          <Field label="Scope">
            <select className="select" value={form.scope} onChange={(e) => setForm((f) => ({ ...f, scope: e.target.value }))}>
              <option value="read">Read only (GET)</option>
              <option value="read_write">Read & write</option>
            </select>
          </Field>
          <Field label="Expires after (days)" hint="Leave blank for no expiry.">
            <input className="input" type="number" min={1} max={3650} value={form.expiresInDays} onChange={(e) => setForm((f) => ({ ...f, expiresInDays: e.target.value }))} />
          </Field>
        </FieldGrid>
        <Field label="Acts as" hint="Calls get exactly this user's permissions. Prefer a dedicated, least-privileged user.">
          <select className="select" value={form.actingUserId} onChange={(e) => setForm((f) => ({ ...f, actingUserId: e.target.value }))}>
            <option value="">Me ({me?.name})</option>
            {users.filter((u) => u.active && u.id !== me?.id).map((u) => (
              <option key={u.id} value={u.id}>{u.name} — {u.role.replace("_", " ").toLowerCase()}</option>
            ))}
          </select>
        </Field>
      </div>
    </Modal>
  );
}

function CreateHookModal({ open, onClose, events, onCreated }: { open: boolean; onClose: () => void; events: string[]; onCreated: (secret: string) => void }) {
  const [form, setForm] = useState({ name: "", url: "https://" });
  const [selected, setSelected] = useState<string[]>(["*"]);
  const groups = Array.from(new Set(events.filter((e) => e !== "webhook.test").map((e) => e.split(".")[0])));
  const toggle = (v: string) =>
    setSelected((s) => {
      if (v === "*") return s.includes("*") ? [] : ["*"];
      const without = s.filter((x) => x !== "*");
      return without.includes(v) ? without.filter((x) => x !== v) : [...without, v];
    });
  const create = useMutation({
    mutationFn: async () => (await api.post("/integrations/webhooks", { name: form.name.trim(), url: form.url.trim(), events: selected })).data,
    onSuccess: (d) => {
      setForm({ name: "", url: "https://" });
      setSelected(["*"]);
      onCreated(d.secret);
    },
    onError: (e: Error) => toast.error(e.message),
  });
  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New webhook"
      width="36rem"
      footer={
        <>
          <button className="btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn-primary" disabled={!form.name.trim() || !/^https?:\/\/.+/.test(form.url) || !selected.length || create.isPending} onClick={() => create.mutate()}>
            Create webhook
          </button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="Name" required>
          <input className="input" value={form.name} maxLength={150} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} autoFocus />
        </Field>
        <Field label="Endpoint URL" required hint="Must be a public HTTPS endpoint that answers 2xx within 10 seconds.">
          <input className="input font-mono text-xs" value={form.url} onChange={(e) => setForm((f) => ({ ...f, url: e.target.value }))} />
        </Field>
        <div>
          <p className="field-label">Events</p>
          <div className="flex flex-wrap gap-2">
            <EventChip label="Everything" active={selected.includes("*")} onClick={() => toggle("*")} />
            {groups.map((g) => (
              <EventChip key={g} label={`${g}.*`} active={selected.includes(`${g}.*`)} onClick={() => toggle(`${g}.*`)} />
            ))}
          </div>
        </div>
      </div>
    </Modal>
  );
}

function EventChip({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded-full px-3 py-1 text-xs font-medium transition-colors"
      style={{
        backgroundColor: active ? "rgb(var(--ring-brand) / 0.14)" : "rgb(var(--panel-border) / 0.06)",
        color: active ? "var(--brand-600)" : "var(--ink-secondary)",
        border: `1px solid ${active ? "rgb(var(--ring-brand) / 0.4)" : "transparent"}`,
      }}
      aria-pressed={active}
    >
      {label}
    </button>
  );
}

function DeliveriesDrawer({ hook, onClose }: { hook: WebhookView | null; onClose: () => void }) {
  const q = useQuery({
    queryKey: ["integrations", "deliveries", hook?.id],
    queryFn: async () => (await api.get<Delivery[]>(`/integrations/webhooks/${hook!.id}/deliveries`)).data,
    enabled: !!hook,
    refetchInterval: hook ? 10_000 : false,
  });
  return (
    <Drawer open={!!hook} onClose={onClose} title="Recent deliveries" subtitle={hook?.name} width="34rem">
      {q.isLoading ? (
        <SkeletonRows rows={5} />
      ) : q.isError ? (
        <QueryError error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.length ? (
        <EmptyState icon={Send} title="No deliveries yet" description="Send a test event to check your endpoint." />
      ) : (
        <ul className="space-y-2">
          {q.data.map((d) => (
            <li key={d.id} className="glass-solid rounded-xl px-3.5 py-3">
              <div className="flex items-center justify-between gap-2">
                <span className="font-mono text-xs" style={{ color: "var(--ink-primary)" }}>{d.event}</span>
                {d.success ? <span className="badge-good">HTTP {d.statusCode}</span> : <span className="badge-critical">{d.statusCode ? `HTTP ${d.statusCode}` : "Failed"}</span>}
              </div>
              <div className="text-xs mt-1.5 flex flex-wrap gap-x-3" style={{ color: "var(--ink-muted)" }}>
                <span>{formatDateTime(d.createdAt)}</span>
                <span>attempt {d.attempt}</span>
                {d.durationMs != null && <span>{d.durationMs} ms</span>}
              </div>
              {d.error && <p className="text-xs mt-1.5" style={{ color: "var(--critical)" }}>{d.error}</p>}
            </li>
          ))}
        </ul>
      )}
    </Drawer>
  );
}
