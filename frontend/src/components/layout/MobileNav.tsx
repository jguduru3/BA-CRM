import { Menu, X } from "lucide-react";
import { useEffect } from "react";
import { NavLink, useLocation } from "react-router-dom";
import { useAuthStore } from "@/lib/auth";
import { Logo } from "@/icons/Logo";
import { useMobileNavStore } from "./mobileNavStore";
import { BOTTOM_NAV, canSee, NAV_GROUPS } from "./nav";

/** Hamburger button for the Topbar (< lg). The drawer itself is rendered once by AppShell. */
export function MobileNavButton() {
  const setOpen = useMobileNavStore((s) => s.setOpen);
  return (
    <button className="btn-icon lg:hidden" onClick={() => setOpen(true)} aria-label="Open menu">
      <Menu size={19} />
    </button>
  );
}

/** Full navigation drawer for small screens; opened from the Topbar hamburger or the bottom bar's "More" tab. */
export function MobileNavDrawer() {
  const open = useMobileNavStore((s) => s.open);
  const setOpen = useMobileNavStore((s) => s.setOpen);
  const role = useAuthStore((s) => s.user?.role);
  const { pathname } = useLocation();

  // Close on navigation and on Escape.
  useEffect(() => {
    setOpen(false);
  }, [pathname, setOpen]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, setOpen]);

  if (!open) return null;

  const linkClass = "flex items-center gap-3 rounded-xl px-3 min-h-[44px] text-sm font-medium transition-colors";

  return (
    <div className="fixed inset-0 z-[80] lg:hidden">
      <div className="fixed inset-0 bg-slate-950/40 backdrop-blur-sm animate-fade-in" onClick={() => setOpen(false)} aria-hidden />
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Navigation"
        className="fixed top-0 left-0 bottom-0 w-[min(288px,86vw)] glass-strong shadow-glass-lg flex flex-col animate-slide-in-right"
        style={{ paddingTop: "env(safe-area-inset-top)", paddingBottom: "env(safe-area-inset-bottom)" }}
      >
        <div className="h-16 flex items-center justify-between px-4 hairline border-b shrink-0">
          <Logo height={30} />
          <button className="btn-icon h-11 w-11" onClick={() => setOpen(false)} aria-label="Close menu">
            <X size={18} />
          </button>
        </div>
        <nav className="flex-1 overflow-y-auto thin-scroll py-3 px-3 space-y-4" aria-label="Main">
          {NAV_GROUPS.map((group) => {
            const items = group.items.filter((i) => canSee(i, role));
            if (items.length === 0) return null;
            return (
              <div key={group.label}>
                <div className="nav-group-label">{group.label}</div>
                <div className="space-y-0.5">
                  {items.map((item) => (
                    <NavLink
                      key={item.to}
                      to={item.to}
                      end={item.to === "/"}
                      onClick={() => setOpen(false)}
                      className={({ isActive }) => `${linkClass} ${isActive ? "nav-active" : "nav-idle"}`}
                    >
                      <item.icon size={18} className="shrink-0" />
                      <span className="truncate">{item.label}</span>
                    </NavLink>
                  ))}
                </div>
              </div>
            );
          })}
        </nav>
        <div className="px-3 py-2.5 space-y-0.5 hairline border-t shrink-0">
          {BOTTOM_NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              onClick={() => setOpen(false)}
              className={({ isActive }) => `${linkClass} ${isActive ? "nav-soft-active" : "nav-idle"}`}
            >
              <item.icon size={18} className="shrink-0" />
              <span>{item.label}</span>
            </NavLink>
          ))}
        </div>
      </div>
    </div>
  );
}
