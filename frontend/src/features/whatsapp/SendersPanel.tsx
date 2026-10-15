import { useMutation, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Pencil, Phone, Plus, Power, Settings2, Trash2, Webhook } from "lucide-react";
import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { toast } from "@/lib/toast";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import type { WaAccount } from "./whatsappTypes";

interface Draft {
  displayPhoneNumber: string;
  phoneNumberId: string;
  businessAccountId: string;
  accessToken: string;
  webhookVerifyToken: string;
  active: boolean;
}
const EMPTY: Draft = { displayPhoneNumber: "", phoneNumberId: "", businessAccountId: "", accessToken: "", webhookVerifyToken: "", active: true };

export function SendersPanel({ accounts }: { accounts: WaAccount[] }) {
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();
  const [editing, setEditing] = useState<WaAccount | null>(null);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [conflict, setConflict] = useState<string | null>(null);

  const startCreate = () => {
    setEditing(null);
    setDraft(EMPTY);
    setConflict(null);
    setOpen(true);
  };
  const startEdit = (a: WaAccount) => {
    setEditing(a);
    setDraft({
      displayPhoneNumber: a.displayPhoneNumber ?? "",
      phoneNumberId: a.phoneNumberId ?? "",
      businessAccountId: a.businessAccountId ?? "",
      accessToken: "",
      webhookVerifyToken: "",
      active: a.active,
    });
    setConflict(null);
    setOpen(true);
  };

  const save = useMutation({
    mutationFn: async () => {
      const body: Record<string, unknown> = {
        displayPhoneNumber: draft.displayPhoneNumber.trim(),
        phoneNumberId: draft.phoneNumberId.trim(),
        businessAccountId: draft.businessAccountId.trim(),
        active: draft.active,
      };
      // Secrets are write-only: only send them when the admin typed a new value.
      if (draft.accessToken.trim()) body.accessToken = draft.accessToken.trim();
      if (draft.webhookVerifyToken.trim()) body.webhookVerifyToken = draft.webhookVerifyToken.trim();
      return editing ? api.put(`/whatsapp/accounts/${editing.id}`, body) : api.post("/whatsapp/accounts", body);
    },
    onSuccess: () => {
      toast.success(editing ? "Sender updated" : "Sender connected");
      qc.invalidateQueries({ queryKey: ["whatsapp", "accounts"] });
      setOpen(false);
    },
    onError: (e: Error) => {
      if (e instanceof ApiError && e.status === 409) setConflict(e.message || "This phone number ID is already connected.");
      else toast.error(e.message);
    },
  });

  const toggle = useMutation({
    mutationFn: async (a: WaAccount) => api.put(`/whatsapp/accounts/${a.id}`, { active: !a.active }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["whatsapp", "accounts"] }),
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/whatsapp/accounts/${id}`),
    onSuccess: () => {
      toast.success("Sender removed");
      qc.invalidateQueries({ queryKey: ["whatsapp", "accounts"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const valid = draft.displayPhoneNumber.trim() && draft.phoneNumberId.trim() && (editing || draft.accessToken.trim());

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm max-w-xl" style={{ color: "var(--ink-muted)" }}>
          Connect one or more WhatsApp Business numbers. People pick the sender when they reply or launch a campaign.
        </p>
        <button className="btn-primary" onClick={startCreate}>
          <Plus size={15} /> Connect number
        </button>
      </div>

      {accounts.length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={Settings2} title="No sender numbers yet" description="Add your Meta Cloud API phone number ID and access token to start messaging." />
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {accounts.map((a) => (
            <div key={a.id} className="glass-card p-4 flex flex-col gap-3">
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-center gap-3 min-w-0">
                  <div className="flex items-center justify-center h-10 w-10 rounded-xl shrink-0" style={{ backgroundColor: "rgb(37 211 102 / 0.12)" }}>
                    <Phone size={17} style={{ color: "#1faa55" }} />
                  </div>
                  <div className="min-w-0">
                    <p className="text-sm font-semibold font-mono tabular-nums truncate" style={{ color: "var(--ink-primary)" }}>
                      {a.displayPhoneNumber ?? "Unnamed sender"}
                    </p>
                    <p className="text-[11px] truncate" style={{ color: "var(--ink-muted)" }}>
                      ID {a.phoneNumberId}
                    </p>
                  </div>
                </div>
                <span className={a.active ? "badge-good" : "badge-neutral"}>{a.active ? "Active" : "Paused"}</span>
              </div>
              <div className="flex flex-wrap gap-2 text-[11px]">
                <span className={a.hasAccessToken ? "badge-brand inline-flex items-center gap-1" : "badge-critical inline-flex items-center gap-1"}>
                  <KeyRound size={11} /> {a.hasAccessToken ? `Token ••••${a.accessTokenHint ?? ""}` : "No access token"}
                </span>
                <span className={a.webhookVerifyTokenSet ? "badge-brand inline-flex items-center gap-1" : "badge-warning inline-flex items-center gap-1"}>
                  <Webhook size={11} /> {a.webhookVerifyTokenSet ? "Webhook verify token set" : "No webhook verify token"}
                </span>
              </div>
              <div className="flex gap-1.5 pt-2 hairline border-t">
                <button className="btn-ghost text-xs" onClick={() => startEdit(a)}>
                  <Pencil size={13} /> Edit
                </button>
                <button className="btn-ghost text-xs" onClick={() => toggle.mutate(a)} disabled={toggle.isPending}>
                  <Power size={13} /> {a.active ? "Pause" : "Activate"}
                </button>
                <button
                  className="btn-ghost text-xs ml-auto"
                  style={{ color: "var(--critical)" }}
                  onClick={async () => {
                    const ok = await confirm("Remove this sender?", `${a.displayPhoneNumber ?? "This number"} will be disconnected. Numbers with history can only be paused.`);
                    if (ok) remove.mutate(a.id);
                  }}
                >
                  <Trash2 size={13} /> Remove
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title={editing ? "Edit sender" : "Connect a WhatsApp number"}
        width="32rem"
        footer={
          <>
            <button className="btn-ghost" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!valid || save.isPending} onClick={() => save.mutate()}>
              {save.isPending ? "Saving…" : editing ? "Save changes" : "Connect"}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          {conflict && (
            <div role="alert" className="rounded-lg px-3 py-2 text-sm" style={{ backgroundColor: "rgb(208 59 59 / 0.1)", color: "var(--critical)" }}>
              {conflict}
            </div>
          )}
          <FieldGrid>
            <Field label="Display number" required>
              <input className="input" placeholder="+91 98765 43210" value={draft.displayPhoneNumber} onChange={(e) => setDraft((d) => ({ ...d, displayPhoneNumber: e.target.value }))} />
            </Field>
            <Field label="Phone number ID" required hint="From Meta → WhatsApp → API setup">
              <input
                className="input font-mono"
                value={draft.phoneNumberId}
                onChange={(e) => {
                  setConflict(null);
                  setDraft((d) => ({ ...d, phoneNumberId: e.target.value }));
                }}
              />
            </Field>
            <Field label="Business account ID">
              <input className="input font-mono" value={draft.businessAccountId} onChange={(e) => setDraft((d) => ({ ...d, businessAccountId: e.target.value }))} />
            </Field>
            <Field label="Status">
              <select className="select" value={draft.active ? "1" : "0"} onChange={(e) => setDraft((d) => ({ ...d, active: e.target.value === "1" }))}>
                <option value="1">Active</option>
                <option value="0">Paused</option>
              </select>
            </Field>
          </FieldGrid>
          <Field
            label="Access token"
            required={!editing}
            hint={editing?.hasAccessToken ? `Saved token ends in ${editing.accessTokenHint ?? "••••"} — leave blank to keep it.` : "System-user permanent token. Stored encrypted, never shown again."}
          >
            <input
              className="input"
              type="password"
              autoComplete="off"
              placeholder={editing?.hasAccessToken ? `•••• •••• ${editing.accessTokenHint ?? ""}` : "EAAG…"}
              value={draft.accessToken}
              onChange={(e) => setDraft((d) => ({ ...d, accessToken: e.target.value }))}
            />
          </Field>
          <Field
            label="Webhook verify token"
            hint={editing?.webhookVerifyTokenSet ? "A verify token is set — leave blank to keep it." : "Any secret string; paste the same value in Meta's webhook settings."}
          >
            <input
              className="input"
              type="password"
              autoComplete="off"
              placeholder={editing?.webhookVerifyTokenSet ? "••••••••" : ""}
              value={draft.webhookVerifyToken}
              onChange={(e) => setDraft((d) => ({ ...d, webhookVerifyToken: e.target.value }))}
            />
          </Field>
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}
