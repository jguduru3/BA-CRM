import { useInfiniteQuery } from "@tanstack/react-query";
import { EyeOff, MessageCircle, MessageSquarePlus, Search, ShieldOff } from "lucide-react";
import { useMemo, useState } from "react";
import { api } from "@/lib/api";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { formatRelative } from "@/lib/format";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tabs } from "@/components/ui/Tabs";
import { useSenderAccounts } from "@/features/campaigns/CampaignForm";
import { ChatPanel, StatusTicks } from "./ChatPanel";
import { SendersPanel } from "./SendersPanel";
import { normalizePhone, type WaAccount, type WaThread } from "./whatsappTypes";

const PAGE = 30;

export default function WhatsAppPage() {
  const user = useAuthStore((s) => s.user);
  const canManageSenders = user?.role === "SUPER_ADMIN" || user?.role === "VP_SALES";
  const canSend = !!user && !isReadOnly(user.role);
  const [tab, setTab] = useState<"inbox" | "senders">("inbox");
  const [selected, setSelected] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  const accounts = useSenderAccounts();
  const senders: WaAccount[] = accounts.data ?? [];

  const inbox = useInfiniteQuery({
    queryKey: ["whatsapp", "inbox"],
    queryFn: async ({ pageParam }) =>
      (await api.get<WaThread[]>("/whatsapp/conversations", { params: { page: pageParam, size: PAGE } })).data,
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.length === PAGE ? all.length : undefined),
    refetchInterval: 20_000,
  });
  const threads = useMemo(() => inbox.data?.pages.flat() ?? [], [inbox.data]);

  const typed = normalizePhone(search);
  const filtered = search.trim()
    ? threads.filter((t) => t.phone.includes(search.replace(/[\s\-().]/g, "")))
    : threads;
  const startNew = typed && !threads.some((t) => t.phone === typed) ? typed : null;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="WhatsApp"
        description="Conversations, delivery receipts and consent across your WhatsApp Business numbers."
        icon={<MessageCircle size={20} />}
      />

      {canManageSenders && (
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: "inbox", label: "Inbox", count: threads.length || undefined },
            { value: "senders", label: "Sender numbers", count: senders.length || undefined },
          ]}
        />
      )}

      {tab === "senders" && canManageSenders ? (
        accounts.isError ? (
          <div className="glass-card">
            <QueryError error={accounts.error} onRetry={() => accounts.refetch()} title="Couldn't load sender numbers" />
          </div>
        ) : (
          <SendersPanel accounts={senders} />
        )
      ) : (
        <div className="glass-card overflow-hidden grid grid-cols-1 lg:grid-cols-[22rem_1fr] h-[calc(100vh-15rem)] min-h-[520px]">
          {/* Conversation list */}
          <aside className={`flex flex-col min-h-0 lg:border-r hairline ${selected ? "hidden lg:flex" : "flex"}`}>
            <div className="p-3 hairline border-b">
              <div className="relative">
                <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: "var(--ink-muted)" }} />
                <input
                  className="input pl-8"
                  placeholder="Search or start a chat: +91…"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </div>
              {startNew && (
                <button
                  className="btn-secondary w-full mt-2 justify-center text-xs"
                  onClick={() => {
                    setSelected(startNew);
                    setSearch("");
                  }}
                >
                  <MessageSquarePlus size={14} /> Open chat with {startNew}
                </button>
              )}
            </div>
            <div className="flex-1 overflow-y-auto thin-scroll">
              {inbox.isError ? (
                <QueryError compact error={inbox.error} onRetry={() => inbox.refetch()} title="Couldn't load conversations" />
              ) : inbox.isLoading ? (
                <div className="p-3 space-y-3">
                  {Array.from({ length: 6 }).map((_, i) => (
                    <Skeleton key={i} className="h-12 w-full" />
                  ))}
                </div>
              ) : filtered.length === 0 ? (
                <EmptyState
                  icon={MessageCircle}
                  title={search ? "No matching conversations" : "No conversations yet"}
                  description={search ? "Type a full number to start a new chat." : "Incoming WhatsApp messages and replies appear here."}
                />
              ) : (
                <ul>
                  {filtered.map((t) => {
                    const m = t.lastMessage;
                    const active = selected === t.phone;
                    return (
                      <li key={t.phone}>
                        <button
                          className="w-full text-left px-4 py-3 hairline border-b transition-colors"
                          style={{ backgroundColor: active ? "rgb(var(--ring-brand) / 0.08)" : undefined }}
                          onClick={() => setSelected(t.phone)}
                        >
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-sm font-semibold font-mono tabular-nums truncate" style={{ color: "var(--ink-primary)" }}>
                              {t.phone}
                            </span>
                            <span className="text-[11px] shrink-0" style={{ color: "var(--ink-muted)" }}>
                              {formatRelative(t.lastMessageAt)}
                            </span>
                          </div>
                          <div className="flex items-center gap-1.5 mt-0.5 text-xs min-w-0" style={{ color: "var(--ink-secondary)" }}>
                            {m?.direction === "outbound" && (
                              <span className="shrink-0" style={{ color: "var(--ink-muted)" }}>
                                <StatusTicks status={m.status} />
                              </span>
                            )}
                            {m?.bodyHidden ? (
                              <span className="inline-flex items-center gap-1 italic" style={{ color: "var(--ink-muted)" }}>
                                <EyeOff size={11} /> Hidden message
                              </span>
                            ) : (
                              <span className="truncate">{m?.type === "template" ? `Template · ${m.templateName}` : m?.body ?? `[${m?.type ?? "message"}]`}</span>
                            )}
                            {t.optedOut && (
                              <span className="ml-auto shrink-0" title="Opted out">
                                <ShieldOff size={12} style={{ color: "var(--critical)" }} />
                              </span>
                            )}
                          </div>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
              {inbox.hasNextPage && (
                <div className="p-3">
                  <button className="btn-ghost w-full justify-center text-xs" disabled={inbox.isFetchingNextPage} onClick={() => inbox.fetchNextPage()}>
                    {inbox.isFetchingNextPage ? "Loading…" : "Load older conversations"}
                  </button>
                </div>
              )}
            </div>
          </aside>

          {/* Conversation */}
          <section className={`min-h-0 flex-col ${selected ? "flex" : "hidden lg:flex"}`}>
            {selected ? (
              <ChatPanel key={selected} phone={selected} accounts={senders} canSend={canSend} onBack={() => setSelected(null)} />
            ) : (
              <div className="flex-1 flex items-center justify-center">
                <EmptyState icon={MessageCircle} title="Pick a conversation" description="Or type a number in international format to start a new chat." />
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
