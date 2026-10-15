import { useMutation, useQuery } from "@tanstack/react-query";
import { Gauge, MessageSquareText, Send, Sparkles, Wand2 } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { toast } from "@/lib/toast";
import type { Lead } from "@/lib/types";
import { Modal } from "@/components/ui/Modal";

interface AiJob {
  id: number;
  type: string;
  status: "pending" | "running" | "done" | "failed";
  resultJson: string | null;
  error: string | null;
  modelUsed: string | null;
}
interface WaAccount {
  id: number;
  displayPhoneNumber: string | null;
  active: boolean;
}

const ACTIONS = [
  { key: "score", label: "Score lead", icon: Gauge, endpoint: "score" },
  { key: "summarize", label: "Summarize", icon: MessageSquareText, endpoint: "summarize" },
  { key: "next-action", label: "Next action", icon: Wand2, endpoint: "next-action" },
  { key: "draft-message", label: "Draft message", icon: Sparkles, endpoint: "draft-message" },
] as const;

function parseResult(json: string | null): Record<string, unknown> | null {
  if (!json) return null;
  try {
    return JSON.parse(json);
  } catch {
    return { output: json };
  }
}

/**
 * Closes the "Claude -> CRM -> WhatsApp/email" loop: calls the existing
 * AI endpoints (score/summarize/next-action/draft-message, powered by
 * whichever provider is configured in Settings -> AI Assistant), then lets
 * the rep send a drafted message straight out over WhatsApp.
 */
export function AiAssistPanel({ lead }: { lead: Lead }) {
  const [activeJob, setActiveJob] = useState<AiJob | null>(null);
  const [sendOpen, setSendOpen] = useState(false);
  const [waAccountId, setWaAccountId] = useState<number | "">("");
  const [phone, setPhone] = useState(lead.phone ?? "");

  const accounts = useQuery({
    queryKey: ["whatsapp", "accounts"],
    queryFn: async () => (await api.get<WaAccount[]>("/whatsapp/accounts")).data,
    retry: 0,
    enabled: sendOpen,
  });

  const run = useMutation({
    mutationFn: async (endpoint: string) => (await api.post<AiJob>(`/ai/leads/${lead.id}/${endpoint}`)).data,
    onSuccess: (job) => {
      setActiveJob(job);
      if (job.status === "failed") toast.error(job.error ?? "AI request failed");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const send = useMutation({
    mutationFn: async () => {
      const text = String(result?.message ?? result?.output ?? "");
      return (
        await api.post<{ status: string; errorMessage?: string | null }>("/whatsapp/send", {
          accountId: waAccountId,
          phone,
          type: "text",
          text,
          relatedType: "Lead",
          relatedId: lead.id,
        })
      ).data;
    },
    onSuccess: (m) => {
      if (m.status === "failed") {
        toast.error(m.errorMessage ?? "WhatsApp did not accept the message");
        return;
      }
      toast.success("Message sent via WhatsApp");
      setSendOpen(false);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const result = activeJob ? parseResult(activeJob.resultJson) : null;

  return (
    <div className="rounded-xl p-3.5 mb-5" style={{ backgroundColor: "rgb(var(--ring-brand) / 0.06)", border: "1px solid rgb(var(--ring-brand) / 0.15)" }}>
      <div className="flex items-center gap-1.5 mb-2.5">
        <Sparkles size={13} style={{ color: "var(--brand-600)" }} />
        <span className="text-xs font-bold uppercase tracking-wide" style={{ color: "var(--brand-600)" }}>
          AI Assist
        </span>
      </div>
      <div className="flex flex-wrap gap-1.5">
        {ACTIONS.map((a) => (
          <button
            key={a.key}
            className="btn-secondary px-2.5 py-1.5 text-xs"
            disabled={run.isPending}
            onClick={() => run.mutate(a.endpoint)}
          >
            <a.icon size={12} /> {a.label}
          </button>
        ))}
      </div>

      {run.isPending && (
        <p className="text-xs mt-3" style={{ color: "var(--ink-muted)" }}>
          Thinking…
        </p>
      )}

      {activeJob?.status === "done" && result && (
        <div className="mt-3 text-sm rounded-lg p-3 space-y-1.5" style={{ backgroundColor: "var(--surface-solid)" }}>
          {"score" in result && (
            <p>
              <strong style={{ color: "var(--ink-primary)" }}>Score: {String(result.score)}/100</strong>
              <br />
              <span style={{ color: "var(--ink-secondary)" }}>{String(result.reasoning ?? "")}</span>
            </p>
          )}
          {"summary" in result && <p style={{ color: "var(--ink-secondary)" }}>{String(result.summary)}</p>}
          {"action" in result && (
            <p>
              <strong style={{ color: "var(--ink-primary)" }}>{String(result.action)}</strong>
              <br />
              <span style={{ color: "var(--ink-secondary)" }}>{String(result.reason ?? "")}</span>
            </p>
          )}
          {"message" in result && (
            <>
              <p className="whitespace-pre-wrap" style={{ color: "var(--ink-secondary)" }}>
                {String(result.message)}
              </p>
              <button className="btn-primary mt-2 px-2.5 py-1.5 text-xs" onClick={() => setSendOpen(true)}>
                <Send size={12} /> Send via WhatsApp
              </button>
            </>
          )}
          {!("score" in result) && !("summary" in result) && !("action" in result) && !("message" in result) && (
            <p style={{ color: "var(--ink-secondary)" }}>{String(result.output ?? "")}</p>
          )}
          {activeJob.modelUsed && (
            <p className="text-[10px] pt-1" style={{ color: "var(--ink-muted)" }}>
              via {activeJob.modelUsed}
            </p>
          )}
        </div>
      )}
      {activeJob?.status === "failed" && (
        <p className="text-xs mt-3" style={{ color: "var(--critical)" }}>
          {activeJob.error || "AI request failed — check Settings → AI Assistant for a configured key."}
        </p>
      )}

      <Modal open={sendOpen} onClose={() => setSendOpen(false)} title="Send via WhatsApp" width="22rem">
        <div className="space-y-4">
          <div>
            <label className="field-label">WhatsApp account</label>
            <select className="select" value={waAccountId} onChange={(e) => setWaAccountId(e.target.value ? Number(e.target.value) : "")}>
              <option value="">— Select —</option>
              {accounts.data
                ?.filter((a) => a.active)
                .map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.displayPhoneNumber ?? `Sender #${a.id}`}
                  </option>
                ))}
            </select>
            {(accounts.isError || (accounts.data && !accounts.data.some((a) => a.active))) && (
              <p className="text-xs mt-1" style={{ color: "var(--critical)" }}>
                {accounts.isError ? accounts.error.message : "No active WhatsApp sender — an admin can connect one under WhatsApp → Sender numbers."}
              </p>
            )}
          </div>
          <div>
            <label className="field-label">Phone number</label>
            <input className="input" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+91XXXXXXXXXX" />
          </div>
          <p className="text-xs p-2.5 rounded-lg" style={{ backgroundColor: "rgb(var(--panel-border) / 0.06)", color: "var(--ink-secondary)" }}>
            {String(result?.message ?? "")}
          </p>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setSendOpen(false)}>
              Cancel
            </button>
            <button className="btn-primary" disabled={!waAccountId || !phone || send.isPending} onClick={() => send.mutate()}>
              <Send size={14} /> Send
            </button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
