import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { LucideIcon } from "lucide-react";
import { Bell, BellRing, CheckCheck, CheckCircle2, ClipboardCheck, Megaphone, MessageCircle, Target, UserPlus, XCircle } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { api, getList } from "@/lib/api";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { AppNotification } from "@/lib/types";
import { EmptyState } from "@/components/ui/EmptyState";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { pushPermission, registerPush, registeredThisSession } from "@/features/settings/push";

const TYPE_ICON: Record<string, { icon: LucideIcon; color: string }> = {
  lead_assigned: { icon: UserPlus, color: "var(--brand-600)" },
  task_due: { icon: ClipboardCheck, color: "var(--warning)" },
  opp_stage_changed: { icon: Target, color: "var(--brand-600)" },
  whatsapp_reply: { icon: MessageCircle, color: "#1faa55" },
  approval_requested: { icon: Megaphone, color: "var(--warning)" },
  campaign_approved: { icon: CheckCircle2, color: "var(--good)" },
  campaign_rejected: { icon: XCircle, color: "var(--critical)" },
};

export function NotificationsPanel() {
  const [open, setOpen] = useState(false);
  const [permission, setPermission] = useState(() => pushPermission());
  const ref = useRef<HTMLDivElement>(null);
  const qc = useQueryClient();

  const unread = useQuery({
    queryKey: ["notifications", "unread-count"],
    queryFn: async () => (await api.get<{ count: number }>("/notifications/unread-count")).data.count,
    refetchInterval: 30_000,
  });

  const list = useQuery({
    queryKey: ["notifications", "list"],
    queryFn: () => getList<AppNotification>("/notifications", { limit: 30 }),
    enabled: open,
  });

  // Push: register this browser's token once per session when the user already granted
  // permission. Without VITE_FIREBASE_* config this is a no-op.
  useEffect(() => {
    if (permission !== "granted" || registeredThisSession()) return;
    registerPush(false).catch(() => {
      /* push is best-effort */
    });
  }, [permission]);

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, []);

  const markAllRead = async () => {
    try {
      await api.post("/notifications/read-all");
      qc.invalidateQueries({ queryKey: ["notifications"] });
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  const markOne = async (n: AppNotification) => {
    if (n.read) return;
    try {
      await api.patch(`/notifications/${n.id}`, { read: true });
      qc.invalidateQueries({ queryKey: ["notifications"] });
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  const enablePush = async () => {
    try {
      const token = await registerPush(true);
      setPermission(pushPermission());
      if (token) toast.success("Desktop notifications are on for this browser");
      else if (Notification.permission === "denied") toast.error("Notifications are blocked in your browser settings");
    } catch (e) {
      toast.error((e as Error).message || "Couldn't enable desktop notifications");
    }
  };

  const count = unread.data ?? 0;
  const items = list.data?.data ?? [];

  return (
    <div className="relative" ref={ref}>
      <button className="btn-icon relative" onClick={() => setOpen((o) => !o)} aria-label={`Notifications${count ? ` (${count} unread)` : ""}`} aria-expanded={open}>
        <Bell size={18} />
        {count > 0 && (
          <span
            className="absolute -top-0.5 -right-0.5 min-w-[16px] h-4 px-1 rounded-full text-[10px] font-bold flex items-center justify-center text-white"
            style={{ backgroundColor: "var(--critical)" }}
          >
            {count > 9 ? "9+" : count}
          </span>
        )}
      </button>
      {open && (
        <div
          className="fixed sm:absolute left-2 right-2 sm:left-auto sm:right-0 top-14 sm:top-auto sm:mt-2 sm:w-96 glass-strong glass-reveal rounded-2xl shadow-glass-lg z-50 animate-slide-up overflow-hidden"
          role="dialog"
          aria-label="Notifications"
        >
          <div className="flex items-center justify-between px-4 py-3 hairline border-b">
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                Notifications
              </span>
              {count > 0 && <span className="badge-brand">{count} new</span>}
            </div>
            <button className="btn-ghost text-xs px-2 py-1" onClick={markAllRead} disabled={count === 0}>
              <CheckCheck size={13} /> Mark all read
            </button>
          </div>

          {permission === "default" && (
            <button
              className="w-full flex items-center gap-2 px-4 py-2.5 text-xs text-left hairline border-b"
              style={{ backgroundColor: "rgb(var(--ring-brand) / 0.06)", color: "var(--ink-secondary)" }}
              onClick={enablePush}
            >
              <BellRing size={14} style={{ color: "var(--brand-600)" }} />
              <span className="flex-1">Get desktop notifications on this browser</span>
              <span className="font-semibold" style={{ color: "var(--brand-600)" }}>
                Enable
              </span>
            </button>
          )}

          <div className="max-h-[26rem] overflow-y-auto thin-scroll">
            {list.isError ? (
              <QueryError compact error={list.error} onRetry={() => list.refetch()} title="Couldn't load notifications" />
            ) : list.isLoading ? (
              <div className="p-4 space-y-3">
                {Array.from({ length: 4 }).map((_, i) => (
                  <Skeleton key={i} className="h-10 w-full" />
                ))}
              </div>
            ) : items.length === 0 ? (
              <EmptyState icon={Bell} title="You're all caught up" description="New assignments, approvals and replies show up here." />
            ) : (
              items.map((n) => {
                const meta = TYPE_ICON[n.type] ?? { icon: Bell, color: "var(--ink-muted)" };
                return (
                  <button
                    key={n.id}
                    onClick={() => markOne(n)}
                    className="w-full text-left px-4 py-3 hairline border-b last:border-b-0 transition-colors"
                    style={{ backgroundColor: n.read ? undefined : "rgb(var(--ring-brand) / 0.06)" }}
                  >
                    <div className="flex items-start gap-3">
                      <div className="flex items-center justify-center h-8 w-8 rounded-lg shrink-0" style={{ backgroundColor: "rgb(var(--panel-border) / 0.07)" }}>
                        <meta.icon size={15} style={{ color: meta.color }} />
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-start gap-2">
                          <p className="text-[13px] font-medium flex-1 min-w-0 truncate" style={{ color: "var(--ink-primary)" }}>
                            {n.title}
                          </p>
                          {!n.read && <span className="h-2 w-2 rounded-full mt-1.5 shrink-0" style={{ backgroundColor: "var(--brand-500)" }} aria-label="Unread" />}
                        </div>
                        {n.body && (
                          <p className="text-xs mt-0.5 line-clamp-2" style={{ color: "var(--ink-secondary)" }}>
                            {n.body}
                          </p>
                        )}
                        <p className="text-[11px] mt-1" style={{ color: "var(--ink-muted)" }}>
                          {formatRelative(n.createdAt)}
                        </p>
                      </div>
                    </div>
                  </button>
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
}
