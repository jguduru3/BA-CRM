import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Inbox, Mail, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

export interface EmailAccountRow {
  id: number;
  emailAddress: string;
  displayName: string | null;
  smtpHost: string | null;
  smtpPort: number | null;
  smtpUser: string | null;
  smtpTls: boolean;
  imapHost: string | null;
  imapPort: number | null;
  imapSsl: boolean;
  active: boolean;
  pollInbox: boolean;
  lastPolledAt: string | null;
  lastPollError: string | null;
  smtpPasswordSet: boolean;
}

interface Draft {
  emailAddress: string;
  displayName: string;
  smtpHost: string;
  smtpPort: number;
  smtpUser: string;
  smtpPassword: string;
  smtpTls: boolean;
  imapHost: string;
  imapPort: number;
  imapSsl: boolean;
  pollInbox: boolean;
}

const EMPTY: Draft = {
  emailAddress: "",
  displayName: "",
  smtpHost: "",
  smtpPort: 587,
  smtpUser: "",
  smtpPassword: "",
  smtpTls: true,
  imapHost: "",
  imapPort: 993,
  imapSsl: true,
  pollInbox: true,
};

const KEY = ["ai-automations", "email-accounts"];

/** Connected mailboxes: send campaigns / sequences / automations, and optionally watch the inbox for replies. */
export function EmailAccountsTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const set = (patch: Partial<Draft>) => setDraft((d) => ({ ...d, ...patch }));

  const accounts = useQuery({
    queryKey: KEY,
    queryFn: async () => (await api.get<EmailAccountRow[]>("/ai-automations/email-accounts")).data,
  });

  const create = useMutation({
    mutationFn: async () =>
      (
        await api.post<EmailAccountRow>("/ai-automations/email-accounts", {
          ...draft,
          imapHost: draft.imapHost.trim() || null,
          pollInbox: draft.imapHost.trim() ? draft.pollInbox : false,
        })
      ).data,
    onSuccess: () => {
      toast.success("Mailbox connected");
      setAdding(false);
      setDraft(EMPTY);
      qc.invalidateQueries({ queryKey: KEY });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const toggle = useMutation({
    mutationFn: async ({ id, pollInbox }: { id: number; pollInbox: boolean }) =>
      api.patch(`/ai-automations/email-accounts/${id}`, { pollInbox }),
    onSuccess: (_r, v) => {
      toast.success(v.pollInbox ? "Watching the inbox for replies" : "Inbox watching turned off");
      qc.invalidateQueries({ queryKey: KEY });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/ai-automations/email-accounts/${id}`),
    onSuccess: () => {
      toast.success("Mailbox removed");
      qc.invalidateQueries({ queryKey: KEY });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const valid =
    /.+@.+\..+/.test(draft.emailAddress.trim()) && draft.smtpHost.trim() !== "" && draft.smtpPassword !== "";

  return (
    <div className="glass-card p-5 sm:p-6 space-y-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-xl shrink-0" style={{ backgroundImage: "var(--brand-gradient)" }}>
            <Mail size={18} color="white" />
          </div>
          <div>
            <span className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
              Email accounts
            </span>
            <p className="text-xs mt-1 max-w-lg" style={{ color: "var(--ink-muted)" }}>
              Mailboxes that send email campaigns, sequence steps and automation emails. With “Watch inbox for replies” the CRM
              checks the inbox every few minutes: replies stop sequences and are logged on the lead or contact, bounces and
              “unsubscribe” replies are honoured.
            </p>
          </div>
        </div>
        {canManage && !adding && (
          <button className="btn-primary" onClick={() => setAdding(true)}>
            <Plus size={14} /> Connect mailbox
          </button>
        )}
      </div>

      {adding && (
        <div className="glass-solid rounded-xl p-4 space-y-4">
          <SectionTitle>Mailbox</SectionTitle>
          <FieldGrid>
            <Field label="Email address" required>
              <input className="input" type="email" value={draft.emailAddress} onChange={(e) => set({ emailAddress: e.target.value })} />
            </Field>
            <Field label="Sender name">
              <input className="input" maxLength={100} value={draft.displayName} onChange={(e) => set({ displayName: e.target.value })} />
            </Field>
          </FieldGrid>
          <SectionTitle>Outgoing (SMTP)</SectionTitle>
          <FieldGrid>
            <Field label="SMTP host" required>
              <input className="input" placeholder="smtp.office365.com" value={draft.smtpHost} onChange={(e) => set({ smtpHost: e.target.value })} />
            </Field>
            <Field label="Port" hint="587 (STARTTLS) or 465 (TLS)">
              <select className="select" value={draft.smtpPort} onChange={(e) => set({ smtpPort: Number(e.target.value) })}>
                {[587, 465, 2525, 25].map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Username" hint="Defaults to the email address">
              <input className="input" autoComplete="off" value={draft.smtpUser} onChange={(e) => set({ smtpUser: e.target.value })} />
            </Field>
            <Field label="Password / app password" required hint="Encrypted at rest; also used for IMAP">
              <input
                className="input"
                type="password"
                autoComplete="new-password"
                value={draft.smtpPassword}
                onChange={(e) => set({ smtpPassword: e.target.value })}
              />
            </Field>
          </FieldGrid>
          <label className="inline-flex items-center gap-2 text-sm" style={{ color: "var(--ink-secondary)" }}>
            <input type="checkbox" className="checkbox" checked={draft.smtpTls} onChange={(e) => set({ smtpTls: e.target.checked })} />
            Require STARTTLS
          </label>
          <SectionTitle>Incoming (IMAP)</SectionTitle>
          <FieldGrid>
            <Field label="IMAP host" hint="Needed to watch the inbox">
              <input className="input" placeholder="outlook.office365.com" value={draft.imapHost} onChange={(e) => set({ imapHost: e.target.value })} />
            </Field>
            <Field label="Port">
              <select className="select" value={draft.imapPort} onChange={(e) => set({ imapPort: Number(e.target.value) })}>
                {[993, 143].map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            </Field>
          </FieldGrid>
          <label
            className="inline-flex items-center gap-2 text-sm"
            style={{ color: draft.imapHost.trim() ? "var(--ink-secondary)" : "var(--ink-muted)" }}
          >
            <input
              type="checkbox"
              className="checkbox"
              disabled={!draft.imapHost.trim()}
              checked={draft.pollInbox && !!draft.imapHost.trim()}
              onChange={(e) => set({ pollInbox: e.target.checked })}
            />
            Watch inbox for replies
          </label>
          <div className="flex justify-end gap-2 pt-3 hairline border-t">
            <button
              className="btn-ghost"
              onClick={() => {
                setAdding(false);
                setDraft(EMPTY);
              }}
            >
              Cancel
            </button>
            <button className="btn-primary" disabled={!valid || create.isPending} onClick={() => create.mutate()}>
              {create.isPending ? "Connecting…" : "Connect mailbox"}
            </button>
          </div>
        </div>
      )}

      {accounts.isError ? (
        <QueryError error={accounts.error} onRetry={() => accounts.refetch()} title="Couldn't load mailboxes" />
      ) : accounts.isLoading ? (
        <Skeleton className="h-24 w-full" />
      ) : (accounts.data ?? []).length === 0 ? (
        <p className="text-sm py-6 text-center" style={{ color: "var(--ink-muted)" }}>
          No mailbox connected yet.
        </p>
      ) : (
        <ul className="rounded-xl hairline border overflow-hidden">
          {accounts.data!.map((a) => (
            <li key={a.id} className="flex flex-wrap items-center gap-3 px-4 py-3 hairline border-b last:border-b-0">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                  {a.displayName ? `${a.displayName} · ` : ""}
                  {a.emailAddress}
                </p>
                <p className="text-[11px] truncate" style={{ color: "var(--ink-muted)" }}>
                  SMTP {a.smtpHost}:{a.smtpPort}
                  {a.imapHost ? ` · IMAP ${a.imapHost}:${a.imapPort}` : " · no IMAP"}
                  {a.pollInbox && a.lastPolledAt ? ` · checked ${formatRelative(a.lastPolledAt)}` : ""}
                </p>
                {a.pollInbox && a.lastPollError && (
                  <p className="text-[11px] mt-0.5 inline-flex items-center gap-1" style={{ color: "var(--critical)" }}>
                    <AlertTriangle size={11} /> {a.lastPollError}
                  </p>
                )}
              </div>
              <label
                className="inline-flex items-center gap-2 text-xs select-none"
                title={a.imapHost ? undefined : "Add an IMAP host to watch this inbox"}
                style={{ color: "var(--ink-secondary)" }}
              >
                <Inbox size={13} />
                <input
                  type="checkbox"
                  className="checkbox"
                  checked={a.pollInbox}
                  disabled={!canManage || !a.imapHost || toggle.isPending}
                  onChange={(e) => toggle.mutate({ id: a.id, pollInbox: e.target.checked })}
                />
                Watch inbox for replies
              </label>
              {canManage && (
                <button
                  className="btn-icon h-8 w-8"
                  aria-label={`Remove ${a.emailAddress}`}
                  onClick={async () => {
                    const ok = await confirm("Remove this mailbox?", `${a.emailAddress} will no longer send or be watched.`);
                    if (ok) remove.mutate(a.id);
                  }}
                >
                  <Trash2 size={14} />
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {confirmNode}
    </div>
  );
}
