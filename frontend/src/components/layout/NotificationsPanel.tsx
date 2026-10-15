import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, CheckCheck } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { api, getList } from "@/lib/api";
import { formatRelative } from "@/lib/format";
import type { AppNotification } from "@/lib/types";
import { EmptyState } from "@/components/ui/EmptyState";

export function NotificationsPanel() {
  const [open, setOpen] = useState(false);
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

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  const markAllRead = async () => {
    await api.post("/notifications/read-all");
    qc.invalidateQueries({ queryKey: ["notifications"] });
  };

  const markOne = async (id: number) => {
    await api.patch(`/notifications/${id}`, { read: true });
    qc.invalidateQueries({ queryKey: ["notifications"] });
  };

  const count = unread.data ?? 0;

  return (
    <div className="relative" ref={ref}>
      <button className="btn-icon relative" onClick={() => setOpen((o) => !o)} aria-label="Notifications">
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
        <div className="absolute right-0 mt-2 w-80 glass-strong glass-reveal rounded-xl shadow-glass-lg z-50 animate-slide-up overflow-hidden">
          <div className="flex items-center justify-between px-4 py-3 hairline border-b">
            <span className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
              Notifications
            </span>
            <button className="btn-ghost text-xs px-2 py-1" onClick={markAllRead}>
              <CheckCheck size={13} /> Mark all read
            </button>
          </div>
          <div className="max-h-96 overflow-y-auto thin-scroll">
            {list.isLoading ? (
              <div className="p-4 text-xs" style={{ color: "var(--ink-muted)" }}>
                Loading…
              </div>
            ) : (list.data?.data.length ?? 0) === 0 ? (
              <EmptyState icon={Bell} title="You're all caught up" description="No notifications yet." />
            ) : (
              list.data!.data.map((n) => (
                <button
                  key={n.id}
                  onClick={() => markOne(n.id)}
                  className="w-full text-left px-4 py-3 hairline border-b last:border-b-0 transition-colors"
                  style={{ backgroundColor: n.read ? undefined : "rgb(var(--ring-brand) / 0.06)" }}
                >
                  <div className="flex items-start gap-2">
                    {!n.read && <span className="h-1.5 w-1.5 rounded-full mt-1.5 shrink-0" style={{ backgroundColor: "var(--brand-500)" }} />}
                    <div className="min-w-0 flex-1">
                      <p className="text-[13px] font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                        {n.title}
                      </p>
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
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
