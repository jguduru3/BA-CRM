import { Handshake, LayoutDashboard, ListChecks, MoreHorizontal, Target } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { NavLink } from "react-router-dom";
import { useMobileNavStore } from "./mobileNavStore";

const TABS: { to: string; label: string; short?: string; icon: LucideIcon }[] = [
  { to: "/", label: "Dashboard", icon: LayoutDashboard },
  { to: "/leads", label: "Leads", icon: Target },
  { to: "/opportunities", label: "Opportunities", short: "Deals", icon: Handshake },
  { to: "/tasks", label: "Tasks", icon: ListChecks },
];

/** Thumb-reachable primary navigation on phones and tablets (< lg). "More" opens the full drawer. */
export function BottomNav() {
  const open = useMobileNavStore((s) => s.open);
  const setOpen = useMobileNavStore((s) => s.setOpen);

  return (
    <nav aria-label="Primary" className="bottom-nav lg:hidden">
      <ul className="grid grid-cols-5 h-[var(--bottom-nav-h)] max-w-xl mx-auto">
        {TABS.map((t) => (
          <li key={t.to} className="flex">
            <NavLink
              to={t.to}
              end={t.to === "/"}
              title={t.label}
              className={({ isActive }) => `bottom-nav-item ${isActive ? "is-active" : ""}`}
            >
              <span className="bottom-nav-pill">
                <t.icon size={20} strokeWidth={2} />
              </span>
              {t.short ? (
                <>
                  <span className="sm:hidden text-[10.5px] font-semibold leading-none">{t.short}</span>
                  <span className="hidden sm:inline text-[10.5px] font-semibold leading-none">{t.label}</span>
                </>
              ) : (
                <span className="text-[10.5px] font-semibold leading-none">{t.label}</span>
              )}
            </NavLink>
          </li>
        ))}
        <li className="flex">
          <button
            type="button"
            className={`bottom-nav-item ${open ? "is-active" : ""}`}
            onClick={() => setOpen(true)}
            aria-label="More navigation"
            aria-haspopup="dialog"
            aria-expanded={open}
          >
            <span className="bottom-nav-pill">
              <MoreHorizontal size={20} strokeWidth={2} />
            </span>
            <span className="text-[10.5px] font-semibold leading-none">More</span>
          </button>
        </li>
      </ul>
    </nav>
  );
}
