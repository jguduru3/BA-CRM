import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { LucideIcon } from "lucide-react";
import { Megaphone, Pause, Play, Plus, Send, Trash2, X } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Campaign } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { StageBadge } from "@/components/ui/Badge";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { Field, FieldGrid } from "@/components/ui/Field";
import { useConfirm } from "@/components/ui/ConfirmDialog";

interface CampaignRow extends Campaign {
  type: string;
  templateName?: string;
  messageBody?: string;
  scheduledAt?: string | null;
  totalContacts: number;
  sentCount: number;
  failedCount: number;
  rejectionReason?: string | null;
  createdAt: string;
}
interface WaAccount {
  id: number;
  displayPhoneNumber: string;
}

const ACTIONS: Record<string, { key: string; label: string; icon: LucideIcon; danger?: boolean }[]> = {
  draft: [
    { key: "submit", label: "Submit for approval", icon: Send },
    { key: "launch", label: "Launch now", icon: Play },
  ],
  pending_approval: [
    { key: "approve", label: "Approve", icon: Play },
    { key: "reject", label: "Reject", icon: X, danger: true },
  ],
  approved: [{ key: "launch", label: "Launch", icon: Play }],
  scheduled: [{ key: "cancel", label: "Cancel", icon: X, danger: true }],
  running: [
    { key: "pause", label: "Pause", icon: Pause },
    { key: "cancel", label: "Cancel", icon: X, danger: true },
  ],
  paused: [
    { key: "resume", label: "Resume", icon: Play },
    { key: "cancel", label: "Cancel", icon: X, danger: true },
  ],
};

export default function CampaignsPage() {
  const [selected, setSelected] = useState<CampaignRow | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<CampaignRow>>({ type: "whatsapp" });
  const [recipients, setRecipients] = useState("");
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["campaigns"],
    queryFn: async () => (await api.get<CampaignRow[]>("/campaigns")).data,
  });
  const accounts = useQuery({
    queryKey: ["whatsapp", "accounts"],
    queryFn: async () => (await api.get<WaAccount[]>("/whatsapp/accounts")).data,
    retry: 0,
  });

  useEffect(() => {
    if (!creating) setDraft({ type: "whatsapp" });
  }, [creating]);

  const create = useMutation({
    mutationFn: async () => (await api.post<CampaignRow>("/campaigns", draft)).data,
    onSuccess: () => {
      toast.success("Campaign drafted");
      qc.invalidateQueries({ queryKey: ["campaigns"] });
      setCreating(false);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const action = useMutation({
    mutationFn: async ({ id, key }: { id: number; key: string }) => api.post(`/campaigns/${id}/${key}`),
    onSuccess: () => {
      toast.success("Campaign updated");
      qc.invalidateQueries({ queryKey: ["campaigns"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/campaigns/${id}`),
    onSuccess: () => {
      toast.success("Campaign deleted");
      qc.invalidateQueries({ queryKey: ["campaigns"] });
      setSelected(null);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const addRecipients = useMutation({
    mutationFn: async () => {
      const entries = recipients
        .split("\n")
        .map((l) => l.trim())
        .filter(Boolean)
        .map((phoneNumber) => ({ phoneNumber }));
      return api.post(`/campaigns/${selected!.id}/contacts`, entries);
    },
    onSuccess: () => {
      toast.success("Recipients added");
      qc.invalidateQueries({ queryKey: ["campaigns"] });
      setRecipients("");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const columns: Column<CampaignRow>[] = [
    { key: "name", header: "Campaign", sortValue: (r) => r.name, render: (r) => <span className="text-sm font-medium">{r.name}</span> },
    { key: "status", header: "Status", sortValue: (r) => r.status, render: (r) => <StageBadge value={r.status.replace("_", " ")} /> },
    { key: "channel", header: "Channel", render: (r) => <span className="text-sm capitalize">{r.type}</span> },
    { key: "totalContacts", header: "Recipients", align: "right", sortValue: (r) => r.totalContacts, render: (r) => <span className="tabular-nums text-sm">{r.totalContacts}</span> },
    { key: "sentCount", header: "Sent", align: "right", sortValue: (r) => r.sentCount, render: (r) => <span className="tabular-nums text-sm" style={{ color: "var(--good)" }}>{r.sentCount}</span> },
    { key: "failedCount", header: "Failed", align: "right", sortValue: (r) => r.failedCount, render: (r) => <span className="tabular-nums text-sm" style={{ color: r.failedCount ? "var(--critical)" : "var(--ink-muted)" }}>{r.failedCount}</span> },
    { key: "createdAt", header: "Created", sortValue: (r) => r.createdAt, render: (r) => <span className="text-sm">{formatDateTime(r.createdAt)}</span> },
  ];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Campaigns"
        description={`${list.data?.length ?? 0} campaigns · Draft → Approval → Scheduled → Sending → Completed`}
        icon={<Megaphone size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New campaign
          </button>
        }
      />

      <DataTable columns={columns} rows={list.data ?? []} loading={list.isLoading} onRowClick={setSelected} emptyTitle="No campaigns yet" />

      <Drawer open={!!selected} onClose={() => setSelected(null)} title={selected?.name ?? ""} subtitle={selected && <StageBadge value={selected.status.replace("_", " ")} />}>
        {selected && (
          <div className="space-y-5">
            {selected.rejectionReason && (
              <div className="rounded-lg px-3 py-2.5 text-sm" style={{ backgroundColor: "rgb(208 59 59 / 0.1)", color: "var(--critical)" }}>
                Rejected: {selected.rejectionReason}
              </div>
            )}
            <div className="grid grid-cols-3 gap-3 text-center">
              <div className="glass-solid rounded-xl p-3">
                <p className="text-lg font-bold tabular-nums">{selected.totalContacts}</p>
                <p className="text-xs" style={{ color: "var(--ink-muted)" }}>Total</p>
              </div>
              <div className="glass-solid rounded-xl p-3">
                <p className="text-lg font-bold tabular-nums" style={{ color: "var(--good)" }}>{selected.sentCount}</p>
                <p className="text-xs" style={{ color: "var(--ink-muted)" }}>Sent</p>
              </div>
              <div className="glass-solid rounded-xl p-3">
                <p className="text-lg font-bold tabular-nums" style={{ color: "var(--critical)" }}>{selected.failedCount}</p>
                <p className="text-xs" style={{ color: "var(--ink-muted)" }}>Failed</p>
              </div>
            </div>

            <div className="flex flex-wrap gap-2">
              {(ACTIONS[selected.status] ?? []).map((a) => (
                <button
                  key={a.key}
                  className={a.danger ? "btn-danger" : "btn-secondary"}
                  onClick={() => action.mutate({ id: selected.id, key: a.key })}
                >
                  <a.icon size={14} /> {a.label}
                </button>
              ))}
              {selected.status === "draft" && (
                <button
                  className="btn-ghost"
                  style={{ color: "var(--critical)" }}
                  onClick={async () => {
                    const ok = await confirm("Delete this campaign?", `"${selected.name}" will be permanently deleted.`);
                    if (ok) remove.mutate(selected.id);
                  }}
                >
                  <Trash2 size={14} /> Delete
                </button>
              )}
            </div>

            {selected.messageBody && (
              <div>
                <p className="field-label">Message</p>
                <p className="text-sm whitespace-pre-wrap p-3 rounded-lg glass-solid" style={{ color: "var(--ink-secondary)" }}>
                  {selected.messageBody}
                </p>
              </div>
            )}

            {selected.status === "draft" && (
              <div>
                <p className="field-label">Add recipients (one phone number per line, E.164)</p>
                <textarea className="textarea" rows={5} value={recipients} onChange={(e) => setRecipients(e.target.value)} placeholder={"+91XXXXXXXXXX\n+91XXXXXXXXXX"} />
                <button className="btn-secondary mt-2" disabled={!recipients.trim()} onClick={() => addRecipients.mutate()}>
                  <Plus size={14} /> Add recipients
                </button>
              </div>
            )}
          </div>
        )}
      </Drawer>

      <Drawer open={creating} onClose={() => setCreating(false)} title="New campaign">
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" value={draft.name ?? ""} onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
          </Field>
          <FieldGrid>
            <Field label="Channel">
              <select className="select" value={draft.type ?? "whatsapp"} onChange={(e) => setDraft((d) => ({ ...d, type: e.target.value }))}>
                <option value="whatsapp">WhatsApp</option>
                <option value="email">Email (not yet dispatched)</option>
              </select>
            </Field>
            <Field label="WhatsApp account" hint={accounts.isError ? "No accounts configured" : undefined}>
              <select
                className="select"
                value={(draft as Partial<CampaignRow> & { waAccountId?: number }).waAccountId ?? ""}
                onChange={(e) => setDraft((d) => ({ ...d, waAccountId: e.target.value ? Number(e.target.value) : undefined }))}
              >
                <option value="">— Default —</option>
                {accounts.data?.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.displayPhoneNumber}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Template name" hint="Must be an approved WhatsApp template" className="sm:col-span-2">
              <input
                className="input"
                value={(draft as Partial<CampaignRow> & { waAccountId?: number }).templateName ?? ""}
                onChange={(e) => setDraft((d) => ({ ...d, templateName: e.target.value }))}
              />
            </Field>
            <Field label="Scheduled for (optional)" className="sm:col-span-2">
              <input
                className="input"
                type="datetime-local"
                onChange={(e) => setDraft((d) => ({ ...d, scheduledAt: e.target.value ? new Date(e.target.value).toISOString() : undefined }))}
              />
            </Field>
          </FieldGrid>
          <Field label="Message preview / notes">
            <textarea
              className="textarea"
              value={(draft as Partial<CampaignRow> & { waAccountId?: number }).messageBody ?? ""}
              onChange={(e) => setDraft((d) => ({ ...d, messageBody: e.target.value }))}
            />
          </Field>
          <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
            <button className="btn-ghost" onClick={() => setCreating(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!draft.name} onClick={() => create.mutate()}>
              Create draft
            </button>
          </div>
        </div>
      </Drawer>
      {confirmNode}
    </div>
  );
}
