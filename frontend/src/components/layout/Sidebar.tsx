import { ChevronsLeft, ChevronsRight } from "lucide-react";
import { useState } from "react";
import { NavLink } from "react-router-dom";
import { useAuthStore } from "@/lib/auth";
import { Logo } from "@/icons/Logo";
import { BOTTOM_NAV, canSee, NAV_GROUPS } from "./nav";

export function Sidebar() {
  const [collapsed, setCollapsed] = useState(false);
  const role = useAuthStore((s) => s.user?.role);

  return (
    <aside
      className={`hidden lg:flex flex-col shrink-0 h-full glass-strong border-r hairline transition-all duration-200 ${
        collapsed ? "w-[72px]" : "w-[248px]"
      }`}
    >
      <div className="h-16 flex items-center px-4 shrink-0 hairline border-b">
        {collapsed ? <Logo height={20} className="mx-auto" /> : <Logo height={34} />}
      </div>

      <nav className="flex-1 overflow-y-auto thin-scroll py-3 px-2.5 space-y-5">
        {NAV_GROUPS.map((group) => {
          const items = group.items.filter((i) => canSee(i, role));
          if (items.length === 0) return null;
          return (
            <div key={group.label}>
              {!collapsed && (
                <div className="px-2.5 mb-1.5 text-[10px] font-bold uppercase tracking-wider" style={{ color: "var(--ink-muted)" }}>
                  {group.label}
                </div>
              )}
              <div className="space-y-0.5">
                {items.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.to === "/"}
                    className={({ isActive }) =>
                      `group relative flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm font-medium transition-all duration-150 ${
                        isActive ? "text-white" : ""
                      }`
                    }
                    style={({ isActive }) => ({
                      backgroundImage: isActive ? "var(--brand-gradient)" : undefined,
                      color: isActive ? "white" : "var(--ink-secondary)",
                      boxShadow: isActive ? "0 4px 14px -4px rgb(var(--ring-brand) / 0.55)" : undefined,
                    })}
                    title={collapsed ? item.label : undefined}
                  >
                    <item.icon size={17} strokeWidth={2} className="shrink-0" />
                    {!collapsed && <span className="truncate">{item.label}</span>}
                  </NavLink>
                ))}
              </div>
            </div>
          );
        })}
      </nav>

      <div className="px-2.5 py-2.5 space-y-0.5 shrink-0 hairline border-t">
        {BOTTOM_NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              `flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm font-medium transition-colors duration-150 ${
                isActive ? "" : ""
              }`
            }
            style={({ isActive }) => ({
              backgroundColor: isActive ? "rgb(var(--ring-brand) / 0.1)" : undefined,
              color: isActive ? "var(--brand-600)" : "var(--ink-secondary)",
            })}
            title={collapsed ? item.label : undefined}
          >
            <item.icon size={17} strokeWidth={2} className="shrink-0" />
            {!collapsed && <span className="truncate">{item.label}</span>}
          </NavLink>
        ))}
        <button
          onClick={() => setCollapsed((c) => !c)}
          className="btn-ghost w-full justify-start mt-1"
          title={collapsed ? "Expand" : "Collapse"}
        >
          {collapsed ? <ChevronsRight size={17} /> : <ChevronsLeft size={17} />}
          {!collapsed && <span>Collapse</span>}
        </button>
      </div>
    </aside>
  );
}
