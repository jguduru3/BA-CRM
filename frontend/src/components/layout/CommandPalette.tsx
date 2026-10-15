import { useQuery } from "@tanstack/react-query";
import { Building2, CheckSquare, CornerDownLeft, Handshake, Search, Target, UserSquare2 } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useNavigate } from "react-router-dom";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { BOTTOM_NAV, canSee, NAV_GROUPS } from "./nav";

interface Hit {
  type: "lead" | "opportunity" | "company" | "contact" | "task";
  id: number;
  title: string;
  subtitle: string | null;
  href: string;
}

const TYPE_ICON: Record<Hit["type"], LucideIcon> = {
  lead: Target,
  opportunity: Handshake,
  company: Building2,
  contact: UserSquare2,
  task: CheckSquare,
};

interface Item {
  key: string;
  icon: LucideIcon;
  title: string;
  subtitle?: string | null;
  group: string;
  href: string;
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

/** ⌘K / Ctrl+K: jump to any page or search records across the CRM. */
export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const role = useAuthStore((s) => s.user?.role);
  const term = useDebounced(q.trim(), 180);

  const search = useQuery({
    queryKey: ["search", term],
    queryFn: async () => (await api.get<{ results: Hit[] }>("/search", { params: { q: term, limit: 5 } })).data.results,
    enabled: open && term.length >= 2,
    staleTime: 15_000,
  });

  const items: Item[] = useMemo(() => {
    const lower = q.trim().toLowerCase();
    const pages = [...NAV_GROUPS.flatMap((g) => g.items), ...BOTTOM_NAV]
      .filter((i) => canSee(i, role))
      .filter((i) => !lower || i.label.toLowerCase().includes(lower))
      .slice(0, lower ? 6 : 8)
      .map<Item>((i) => ({ key: `page:${i.to}`, icon: i.icon, title: i.label, group: "Go to", href: i.to }));
    const records = (term.length >= 2 ? search.data ?? [] : []).map<Item>((h) => ({
      key: `${h.type}:${h.id}`,
      icon: TYPE_ICON[h.type],
      title: h.title,
      subtitle: h.subtitle,
      group: h.type.charAt(0).toUpperCase() + h.type.slice(1) + "s",
      href: h.href,
    }));
    return [...records, ...pages];
  }, [q, term, search.data, role]);

  useEffect(() => {
    if (open) {
      setQ("");
      setActive(0);
      setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);
  useEffect(() => setActive(0), [q, search.data]);
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>(`[data-idx="${active}"]`)?.scrollIntoView({ block: "nearest" });
  }, [active]);

  if (!open) return null;

  const go = (item: Item | undefined) => {
    if (!item) return;
    onClose();
    navigate(item.href);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => Math.min(items.length - 1, a + 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(0, a - 1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      go(items[active]);
    } else if (e.key === "Escape") {
      onClose();
    }
  };

  let lastGroup = "";
  return createPortal(
    <div className="fixed inset-0 z-[90] flex items-start justify-center p-4 pt-[12vh]">
      <div className="fixed inset-0 bg-slate-950/40 backdrop-blur-sm animate-fade-in" onClick={onClose} aria-hidden />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Search"
        className="relative w-full max-w-xl glass-strong glass-reveal rounded-2xl shadow-glass-lg animate-slide-up overflow-hidden"
        onKeyDown={onKeyDown}
      >
        <div className="flex items-center gap-3 px-4 hairline border-b">
          <Search size={18} style={{ color: "var(--ink-muted)" }} />
          <input
            ref={inputRef}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search leads, deals, companies, contacts, tasks… or jump to a page"
            className="flex-1 bg-transparent py-4 text-[15px] outline-none"
            style={{ color: "var(--ink-primary)" }}
            role="combobox"
            aria-expanded="true"
            aria-controls="cmdk-list"
            aria-activedescendant={items[active] ? `cmdk-${active}` : undefined}
          />
          <kbd className="hidden sm:inline text-[10px] font-semibold rounded-md px-1.5 py-0.5 glass-solid" style={{ color: "var(--ink-muted)" }}>
            ESC
          </kbd>
        </div>
        <div ref={listRef} id="cmdk-list" role="listbox" className="max-h-[52vh] overflow-y-auto thin-scroll py-2">
          {term.length >= 2 && search.isFetching && !search.data && (
            <p className="px-4 py-3 text-sm" style={{ color: "var(--ink-muted)" }}>Searching…</p>
          )}
          {items.length === 0 && !search.isFetching && (
            <p className="px-4 py-8 text-sm text-center" style={{ color: "var(--ink-muted)" }}>No matches for “{q}”.</p>
          )}
          {items.map((item, idx) => {
            const header = item.group !== lastGroup ? item.group : null;
            lastGroup = item.group;
            const Icon = item.icon;
            const isActive = idx === active;
            return (
              <div key={item.key}>
                {header && (
                  <div className="px-4 pt-2.5 pb-1 text-[10px] font-bold uppercase tracking-wider" style={{ color: "var(--ink-muted)" }}>
                    {header}
                  </div>
                )}
                <button
                  id={`cmdk-${idx}`}
                  data-idx={idx}
                  role="option"
                  aria-selected={isActive}
                  onMouseMove={() => setActive(idx)}
                  onClick={() => go(item)}
                  className="w-full flex items-center gap-3 px-4 py-2.5 text-left transition-colors"
                  style={{ backgroundColor: isActive ? "rgb(var(--ring-brand) / 0.1)" : undefined }}
                >
                  <span
                    className="flex items-center justify-center h-8 w-8 rounded-lg shrink-0"
                    style={{ backgroundColor: "rgb(var(--ring-brand) / 0.1)", color: "var(--brand-600)" }}
                  >
                    <Icon size={15} />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>{item.title}</span>
                    {item.subtitle && <span className="block text-xs truncate" style={{ color: "var(--ink-muted)" }}>{item.subtitle}</span>}
                  </span>
                  {isActive && <CornerDownLeft size={14} style={{ color: "var(--ink-muted)" }} />}
                </button>
              </div>
            );
          })}
        </div>
      </div>
    </div>,
    document.body
  );
}
