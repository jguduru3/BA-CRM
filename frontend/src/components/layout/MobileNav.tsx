import { Menu, X } from "lucide-react";
import { useState } from "react";
import { NavLink } from "react-router-dom";
import { useAuthStore } from "@/lib/auth";
import { Logo } from "@/icons/Logo";
import { BOTTOM_NAV, canSee, NAV_GROUPS } from "./nav";

export function MobileNav() {
  const [open, setOpen] = useState(false);
  const role = useAuthStore((s) => s.user?.role);

  return (
    <>
      <button className="btn-icon lg:hidden" onClick={() => setOpen(true)} aria-label="Open menu">
        <Menu size={19} />
      </button>
      {open && (
        <div className="fixed inset-0 z-[80] lg:hidden">
          <div className="fixed inset-0 bg-slate-950/40 backdrop-blur-sm" onClick={() => setOpen(false)} />
          <div className="fixed top-0 left-0 bottom-0 w-[260px] glass-strong flex flex-col animate-slide-in-right">
            <div className="h-16 flex items-center justify-between px-4 hairline border-b">
              <Logo height={30} />
              <button className="btn-icon" onClick={() => setOpen(false)} aria-label="Close">
                <X size={18} />
              </button>
            </div>
            <nav className="flex-1 overflow-y-auto thin-scroll py-3 px-2.5 space-y-5">
              {NAV_GROUPS.map((group) => {
                const items = group.items.filter((i) => canSee(i, role));
                if (items.length === 0) return null;
                return (
                  <div key={group.label}>
                    <div className="px-2.5 mb-1.5 text-[10px] font-bold uppercase tracking-wider" style={{ color: "var(--ink-muted)" }}>
                      {group.label}
                    </div>
                    <div className="space-y-0.5">
                      {items.map((item) => (
                        <NavLink
                          key={item.to}
                          to={item.to}
                          end={item.to === "/"}
                          onClick={() => setOpen(false)}
                          className="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm font-medium"
                          style={({ isActive }) => ({
                            backgroundImage: isActive ? "var(--brand-gradient)" : undefined,
                            color: isActive ? "white" : "var(--ink-secondary)",
                          })}
                        >
                          <item.icon size={17} />
                          <span>{item.label}</span>
                        </NavLink>
                      ))}
                    </div>
                  </div>
                );
              })}
            </nav>
            <div className="px-2.5 py-2.5 space-y-0.5 hairline border-t">
              {BOTTOM_NAV.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  onClick={() => setOpen(false)}
                  className="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm font-medium"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  <item.icon size={17} />
                  <span>{item.label}</span>
                </NavLink>
              ))}
            </div>
          </div>
        </div>
      )}
    </>
  );
}
