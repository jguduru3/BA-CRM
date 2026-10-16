import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertCircle, ArrowLeft, Check, CheckCheck, Clock, EyeOff, Send, ShieldCheck, ShieldOff, UserRound } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api, ApiError } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { useReason } from "@/components/ui/ReasonDialog";
import type { ConsentView, WaAccount, WaMessage } from "./whatsappTypes";

/** Delivery ticks: ✓ sent, ✓✓ delivered, blue ✓✓ read, ⚠ failed. */
export function StatusTicks({ status, error }: { status: string; error?: string | null }) {
  switch (status) {
    case "read":
      return <CheckCheck size={14} aria-label="Read" style={{ color: "#53bdeb" }} />;
    case "delivered":
      return <CheckCheck size={14} aria-label="Delivered" style={{ opacity: 0.75 }} />;
    case "sent":
      return <Check size={14} aria-label="Sent" style={{ opacity: 0.75 }} />;
    case "failed":
      return (
        <span title={error ?? "Not delivered"} className="inline-flex">
          <AlertCircle size={14} aria-label="Failed" style={{ color: "#ffb4b4" }} />
        </span>
      );
    default:
      return <Clock size={13} aria-label="Pending" style={{ opacity: 0.7 }} />;
  }
}

export function ConsentBadge({ consent }: { consent: ConsentView | undefined }) {
  if (!consent) return null;
  if (consent.optedOut)
    return (
      <span className="badge-critical inline-flex items-center gap-1" title={consent.reason ?? undefined}>
        <ShieldOff size={11} /> Opted out
      </span>
    );
  if (consent.status === "opted_in")
    return (
      <span className="badge-good inline-flex items-center gap-1">
        <ShieldCheck size={11} /> Opted in
      </span>
    );
  return <span className="badge-neutral">No consent record</span>;
}

function dayLabel(iso: string): string {
  const d = new Date(iso);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return "Today";
  if (d.toDateString() === yesterday.toDateString()) return "Yesterday";
  return d.toLocaleDateString(undefined, { day: "numeric", month: "short", year: d.getFullYear() === today.getFullYear() ? undefined : "numeric" });
}

export function ChatPanel({
  phone,
  accounts,
  canSend,
  onBack,
}: {
  phone: string;
  accounts: WaAccount[];
  canSend: boolean;
  onBack?: () => void;
}) {
  const qc = useQueryClient();
  const { askReason, node: reasonNode } = useReason();
  const [text, setText] = useState("");
  const active = useMemo(() => accounts.filter((a) => a.active), [accounts]);
  const [accountId, setAccountId] = useState<number | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const thread = useQuery({
    queryKey: ["whatsapp", "thread", phone],
    queryFn: async () => (await api.get<WaMessage[]>(`/whatsapp/conversations/phone/${encodeURIComponent(phone)}`, { params: { limit: 200 } })).data,
    refetchInterval: 15_000,
  });
  const consent = useQuery({
    queryKey: ["whatsapp", "consent", phone],
    queryFn: async () => (await api.get<ConsentView>(`/whatsapp/consent/${encodeURIComponent(phone)}`)).data,
  });

  const messages = useMemo(() => [...(thread.data ?? [])].reverse(), [thread.data]);
  const lastInboundAccount = useMemo(() => [...messages].reverse().find((m) => m.accountId)?.accountId ?? null, [messages]);

  // Default sender: the number this contact last talked to, else the first active sender.
  useEffect(() => {
    setAccountId((cur) => {
      if (cur && active.some((a) => a.id === cur)) return cur;
      if (lastInboundAccount && active.some((a) => a.id === lastInboundAccount)) return lastInboundAccount;
      return active[0]?.id ?? null;
    });
  }, [active, lastInboundAccount]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ block: "end" });
  }, [messages.length, phone]);

  const link = messages.find((m) => m.relatedType && m.relatedId);

  const send = useMutation({
    mutationFn: async () =>
      (
        await api.post<WaMessage>("/whatsapp/send", {
          accountId,
          phone,
          type: "text",
          text: text.trim(),
          ...(link?.relatedType === "Lead" || link?.relatedType === "Contact" ? { relatedType: link.relatedType, relatedId: link.relatedId } : {}),
        })
      ).data,
    onSuccess: (m) => {
      if (m.status === "failed") toast.error(m.errorMessage ?? "WhatsApp did not accept the message");
      setText("");
      qc.invalidateQueries({ queryKey: ["whatsapp"] });
    },
    onError: (e: Error) => {
      toast.error(e.message);
      if (e instanceof ApiError && e.status === 409) qc.invalidateQueries({ queryKey: ["whatsapp", "consent", phone] });
    },
  });

  const setConsent = useMutation({
    mutationFn: async ({ status, reason }: { status: "opted_in" | "opted_out"; reason: string }) =>
      (await api.post<ConsentView>("/whatsapp/consent", { phone, status, reason })).data,
    onSuccess: (v) => {
      qc.setQueryData(["whatsapp", "consent", phone], v);
      qc.invalidateQueries({ queryKey: ["whatsapp", "inbox"] });
      toast.success(v.optedOut ? "Marked as opted out" : "Opt-in recorded");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const changeConsent = async () => {
    const optOut = !consent.data?.optedOut;
    const reason = await askReason({
      title: optOut ? "Opt this number out?" : "Record an opt-in?",
      description: optOut
        ? "No WhatsApp messages or campaigns will be sent to this number until it opts back in."
        : "Only record an opt-in when the person has explicitly agreed to receive WhatsApp messages again.",
      label: optOut ? "Reason (e.g. asked on a call)" : "How was consent given?",
      confirmLabel: optOut ? "Opt out" : "Record opt-in",
      danger: optOut,
    });
    if (reason) setConsent.mutate({ status: optOut ? "opted_out" : "opted_in", reason });
  };

  const optedOut = consent.data?.optedOut ?? false;
  const blockedReason = !canSend
    ? "You have read-only access to WhatsApp."
    : active.length === 0
    ? "No active sender number — connect one under Senders."
    : optedOut
    ? "This number opted out. Record a new opt-in to message it."
    : null;

  let lastDay = "";
  return (
    <div className="flex flex-col h-full min-h-[420px]">
      <div className="flex flex-wrap items-center gap-3 px-4 py-3 hairline border-b">
        {onBack && (
          <button className="btn-icon h-8 w-8 lg:hidden" onClick={onBack} aria-label="Back to conversations">
            <ArrowLeft size={16} />
          </button>
        )}
        <div className="flex items-center justify-center h-9 w-9 rounded-full shrink-0" style={{ backgroundColor: "rgb(var(--ring-brand) / 0.12)" }}>
          <UserRound size={17} style={{ color: "var(--brand-600)" }} />
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold font-mono tabular-nums truncate" style={{ color: "var(--ink-primary)" }}>
            {phone}
          </p>
          <div className="flex flex-wrap items-center gap-2 mt-0.5">
            <ConsentBadge consent={consent.data} />
            {link?.relatedType === "Lead" && (
              <Link to={`/leads?open=${link.relatedId}`} className="text-[11px] underline" style={{ color: "var(--brand-600)" }}>
                Open lead
              </Link>
            )}
            {link?.relatedType === "Contact" && (
              <Link to={`/contacts?open=${link.relatedId}`} className="text-[11px] underline" style={{ color: "var(--brand-600)" }}>
                Open contact
              </Link>
            )}
          </div>
        </div>
        {canSend && consent.data && (
          <button className="btn-ghost text-xs" onClick={changeConsent} disabled={setConsent.isPending}>
            {optedOut ? <ShieldCheck size={13} /> : <ShieldOff size={13} />}
            {optedOut ? "Record opt-in" : "Opt out"}
          </button>
        )}
      </div>

      <div className="flex-1 overflow-y-auto thin-scroll px-3 sm:px-4 py-4 space-y-1.5" style={{ backgroundColor: "rgb(var(--panel-border) / 0.03)" }}>
        {thread.isError ? (
          <QueryError compact error={thread.error} onRetry={() => thread.refetch()} title="Couldn't load the conversation" />
        ) : thread.isLoading ? (
          <div className="space-y-3">
            <Skeleton className="h-10 w-2/3" />
            <Skeleton className="h-10 w-1/2 ml-auto" />
            <Skeleton className="h-10 w-3/5" />
          </div>
        ) : messages.length === 0 ? (
          <p className="text-sm text-center py-16" style={{ color: "var(--ink-muted)" }}>
            No messages with this number yet.
          </p>
        ) : (
          messages.map((m) => {
            const out = m.direction === "outbound";
            const day = dayLabel(m.createdAt);
            const showDay = day !== lastDay;
            lastDay = day;
            const sender = accounts.find((a) => a.id === m.accountId);
            return (
              <div key={m.id}>
                {showDay && (
                  <div className="flex justify-center my-3">
                    <span className="text-[11px] px-2.5 py-0.5 rounded-full glass-solid" style={{ color: "var(--ink-muted)" }}>
                      {day}
                    </span>
                  </div>
                )}
                <div className={`flex ${out ? "justify-end" : "justify-start"}`}>
                  <div
                    className={`max-w-[85%] sm:max-w-[70%] px-3.5 py-2 text-sm shadow-sm ${out ? "rounded-2xl rounded-br-md" : "rounded-2xl rounded-bl-md glass-solid"}`}
                    style={{
                      backgroundImage: out ? "var(--brand-gradient)" : undefined,
                      color: out ? "white" : "var(--ink-primary)",
                    }}
                  >
                    {m.bodyHidden ? (
                      <p className="inline-flex items-center gap-1.5 italic opacity-80">
                        <EyeOff size={13} /> Message hidden — visible to its sender, the record owner and managers
                      </p>
                    ) : m.type === "template" ? (
                      <p>
                        <span className="text-[11px] uppercase tracking-wide opacity-80">Template</span>{" "}
                        <span className="font-mono">{m.templateName}</span>
                      </p>
                    ) : (
                      <p className="whitespace-pre-wrap break-words">{m.body || `[${m.type}]`}</p>
                    )}
                    <div className={`flex items-center gap-1.5 mt-1 text-[10px] ${out ? "justify-end" : ""}`} style={{ opacity: 0.8 }}>
                      {out && sender && accounts.length > 1 && <span className="truncate max-w-[8rem]">via {sender.displayPhoneNumber}</span>}
                      {m.campaignId && <span>campaign</span>}
                      <span title={formatDateTime(m.createdAt)}>
                        {new Date(m.createdAt).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}
                      </span>
                      {out && <StatusTicks status={m.status} error={m.errorMessage} />}
                    </div>
                  </div>
                </div>
              </div>
            );
          })
        )}
        <div ref={bottomRef} />
      </div>

      <div className="px-3 sm:px-4 py-3 hairline border-t space-y-2">
        {blockedReason ? (
          <p className="text-xs text-center py-1" style={{ color: optedOut ? "var(--critical)" : "var(--ink-muted)" }}>
            {blockedReason}
          </p>
        ) : (
          <form
            className="flex items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (text.trim() && accountId) send.mutate();
            }}
          >
            {active.length > 1 && (
              <select
                className="select !w-auto max-w-[9.5rem] text-xs"
                aria-label="Send from"
                value={accountId ?? ""}
                onChange={(e) => setAccountId(Number(e.target.value))}
              >
                {active.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.displayPhoneNumber ?? `Sender #${a.id}`}
                  </option>
                ))}
              </select>
            )}
            <textarea
              className="textarea flex-1 !min-h-[40px] max-h-32 resize-none"
              rows={1}
              placeholder="Type a message"
              maxLength={4096}
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey) {
                  e.preventDefault();
                  if (text.trim() && accountId && !send.isPending) send.mutate();
                }
              }}
            />
            <button type="submit" className="btn-primary h-10 w-10 !px-0 justify-center shrink-0" disabled={!text.trim() || !accountId || send.isPending} aria-label="Send">
              <Send size={15} />
            </button>
          </form>
        )}
        <p className="text-[10px] text-center" style={{ color: "var(--ink-muted)" }}>
          Free-form messages only reach people who wrote to you in the last 24 hours — otherwise use a campaign template.
        </p>
      </div>
      {reasonNode}
    </div>
  );
}
