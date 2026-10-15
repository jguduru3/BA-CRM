import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MessageCircle, Phone, Plus, Send, Settings2, Trash2 } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { Field, FieldGrid } from "@/components/ui/Field";
import { Modal } from "@/components/ui/Modal";
import { SearchInput } from "@/components/ui/SearchInput";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";

interface WaAccount {
  id: number;
  displayPhoneNumber: string;
  phoneNumberId: string;
  businessAccountId: string;
  active: boolean;
}
interface WaMessage {
  id: number;
  direction: "inbound" | "outbound";
  body: string | null;
  type: string;
  status: string;
  fromNumber: string | null;
  toNumber: string | null;
  createdAt: string;
}

export default function WhatsAppPage() {
  const [tab, setTab] = useState<"conversations" | "accounts">("conversations");
  const [phone, setPhone] = useState("");
  const [sendText, setSendText] = useState("");
  const [accountModal, setAccountModal] = useState(false);
  const [accountDraft, setAccountDraft] = useState<Partial<WaAccount & { accessToken: string; webhookVerifyToken: string }>>({});
  const user = useAuthStore((s) => s.user);
  const isAdmin = user?.role === "SUPER_ADMIN" || user?.role === "VP_SALES";
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const accounts = useQuery({
    queryKey: ["whatsapp", "accounts"],
    queryFn: async () => (await api.get<WaAccount[]>("/whatsapp/accounts")).data,
    enabled: isAdmin,
    retry: 0,
  });

  const thread = useQuery({
    queryKey: ["whatsapp", "conversation", phone],
    queryFn: async () => (await api.get<WaMessage[]>(`/whatsapp/conversations/phone/${encodeURIComponent(phone)}`)).data,
    enabled: phone.length > 5,
  });

  const send = useMutation({
    mutationFn: async () => {
      const accountId = accounts.data?.[0]?.id;
      if (!accountId) throw new Error("No active WhatsApp account configured");
      return api.post("/whatsapp/send", { accountId, phone, type: "text", text: sendText });
    },
    onSuccess: () => {
      toast.success("Message sent");
      setSendText("");
      qc.invalidateQueries({ queryKey: ["whatsapp", "conversation", phone] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const createAccount = useMutation({
    mutationFn: async () => api.post("/whatsapp/accounts", accountDraft),
    onSuccess: () => {
      toast.success("Account saved");
      qc.invalidateQueries({ queryKey: ["whatsapp", "accounts"] });
      setAccountModal(false);
      setAccountDraft({});
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteAccount = useMutation({
    mutationFn: async (id: number) => api.delete(`/whatsapp/accounts/${id}`),
    onSuccess: () => {
      toast.success("Account removed");
      qc.invalidateQueries({ queryKey: ["whatsapp", "accounts"] });
    },
  });

  return (
    <div className="flex flex-col gap-5">
      <PageHeader title="WhatsApp" description="Meta WhatsApp Business Cloud API" icon={<MessageCircle size={20} />} />

      <div className="flex items-center justify-between flex-wrap gap-3">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: "conversations", label: "Conversations" },
            ...(isAdmin ? [{ value: "accounts" as const, label: "Accounts" }] : []),
          ]}
        />
      </div>

      {tab === "conversations" ? (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div className="glass-card p-4 lg:col-span-1">
            <p className="field-label">Look up a conversation</p>
            <SearchInput value={phone} onChange={setPhone} placeholder="+91XXXXXXXXXX" />
            <p className="text-xs mt-2" style={{ color: "var(--ink-muted)" }}>
              Enter a phone number in E.164 format to view its message thread.
            </p>
          </div>
          <div className="glass-card p-4 lg:col-span-2 flex flex-col min-h-[420px]">
            {phone.length <= 5 ? (
              <EmptyState icon={Phone} title="Enter a phone number" description="Search a contact's number to view the thread." />
            ) : thread.isLoading ? (
              <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
                Loading…
              </p>
            ) : (thread.data ?? []).length === 0 ? (
              <EmptyState icon={MessageCircle} title="No messages yet" description={`No WhatsApp history with ${phone}.`} />
            ) : (
              <div className="flex-1 overflow-y-auto thin-scroll space-y-2 mb-3">
                {[...(thread.data ?? [])].reverse().map((m) => (
                  <div key={m.id} className={`flex ${m.direction === "outbound" ? "justify-end" : "justify-start"}`}>
                    <div
                      className="max-w-[75%] rounded-2xl px-3.5 py-2 text-sm"
                      style={{
                        backgroundImage: m.direction === "outbound" ? "var(--brand-gradient)" : undefined,
                        backgroundColor: m.direction === "outbound" ? undefined : "rgb(var(--panel-border) / 0.07)",
                        color: m.direction === "outbound" ? "white" : "var(--ink-primary)",
                      }}
                    >
                      <p>{m.body || `[${m.type}]`}</p>
                      <p className="text-[10px] mt-1 opacity-70">{formatDateTime(m.createdAt)}</p>
                    </div>
                  </div>
                ))}
              </div>
            )}
            {phone.length > 5 && (
              <form
                className="flex gap-2 pt-3 hairline border-t"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (sendText.trim()) send.mutate();
                }}
              >
                <input className="input flex-1" placeholder="Type a message…" value={sendText} onChange={(e) => setSendText(e.target.value)} />
                <button type="submit" className="btn-primary px-3" disabled={!sendText.trim() || send.isPending}>
                  <Send size={15} />
                </button>
              </form>
            )}
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <button className="btn-primary self-start" onClick={() => setAccountModal(true)}>
            <Plus size={15} /> Connect WhatsApp account
          </button>
          {(accounts.data ?? []).length === 0 ? (
            <div className="glass-card">
              <EmptyState icon={Settings2} title="No WhatsApp accounts connected" description="Add your Meta Cloud API credentials to start sending." />
            </div>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {accounts.data!.map((a) => (
                <div key={a.id} className="glass-card p-4">
                  <div className="flex items-start justify-between">
                    <div>
                      <p className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                        {a.displayPhoneNumber}
                      </p>
                      <p className="text-xs mt-0.5" style={{ color: "var(--ink-muted)" }}>
                        Phone number ID: {a.phoneNumberId}
                      </p>
                    </div>
                    <button
                      className="btn-icon h-7 w-7"
                      style={{ color: "var(--critical)" }}
                      onClick={async () => {
                        const ok = await confirm("Remove this account?", `"${a.displayPhoneNumber}" will be disconnected.`);
                        if (ok) deleteAccount.mutate(a.id);
                      }}
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                  <span className={a.active ? "badge-good mt-2 inline-block" : "badge-neutral mt-2 inline-block"}>
                    {a.active ? "Active" : "Inactive"}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <Modal open={accountModal} onClose={() => setAccountModal(false)} title="Connect WhatsApp account" width="28rem">
        <div className="space-y-4">
          <FieldGrid>
            <Field label="Display phone number" required>
              <input
                className="input"
                value={accountDraft.displayPhoneNumber ?? ""}
                onChange={(e) => setAccountDraft((d) => ({ ...d, displayPhoneNumber: e.target.value }))}
              />
            </Field>
            <Field label="Phone number ID" required>
              <input className="input" value={accountDraft.phoneNumberId ?? ""} onChange={(e) => setAccountDraft((d) => ({ ...d, phoneNumberId: e.target.value }))} />
            </Field>
            <Field label="Business account ID">
              <input
                className="input"
                value={accountDraft.businessAccountId ?? ""}
                onChange={(e) => setAccountDraft((d) => ({ ...d, businessAccountId: e.target.value }))}
              />
            </Field>
            <Field label="Webhook verify token">
              <input
                className="input"
                value={accountDraft.webhookVerifyToken ?? ""}
                onChange={(e) => setAccountDraft((d) => ({ ...d, webhookVerifyToken: e.target.value }))}
              />
            </Field>
          </FieldGrid>
          <Field label="Access token" required hint="Meta Cloud API permanent or system-user token">
            <input className="input" type="password" value={accountDraft.accessToken ?? ""} onChange={(e) => setAccountDraft((d) => ({ ...d, accessToken: e.target.value }))} />
          </Field>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setAccountModal(false)}>
              Cancel
            </button>
            <button
              className="btn-primary"
              disabled={!accountDraft.displayPhoneNumber || !accountDraft.phoneNumberId || !accountDraft.accessToken}
              onClick={() => createAccount.mutate()}
            >
              Save account
            </button>
          </div>
        </div>
      </Modal>
      {confirmNode}
    </div>
  );
}
