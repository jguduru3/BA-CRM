import { ChevronsLeft, ChevronsRight } from "lucide-react";
import { useState } from "react";
import { NavLink } from "react-router-dom";
import { useAuthStore } from "@/lib/auth";
import { Logo } from "@/icons/Logo";
import { BOTTOM_NAV, canSee, NAV_GROUPS } from "./nav";

const COLLAPSE_KEY = "bettercrm.sidebar.collapsed";

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSE_KEY) === "1";
  } catch {
    return false;
  }
}

export function Sidebar() {
  const [collapsed, setCollapsedState] = useState(readCollapsed);
  const role = useAuthStore((s) => s.user?.role);

  const setCollapsed = (v: boolean) => {
    setCollapsedState(v);
    try {
      localStorage.setItem(COLLAPSE_KEY, v ? "1" : "0");
    } catch {
      /* storage unavailable */
    }
  };

  const linkBase = `group relative flex items-center gap-2.5 rounded-xl py-2 text-sm font-medium transition-all duration-150 ${
    collapsed ? "justify-center px-0 h-10" : "px-2.5"
  }`;

  return (
    <aside
      className={`hidden lg:flex flex-col shrink-0 h-full glass-strong border-y-0 border-l-0 border-r hairline transition-[width] duration-200 ${
        collapsed ? "w-[72px]" : "w-[248px]"
      }`}
      aria-label="Sidebar"
    >
      <div className={`h-16 flex items-center shrink-0 hairline border-b ${collapsed ? "justify-center px-2" : "px-5"}`}>
        {collapsed ? <Logo height={22} /> : <Logo height={32} />}
      </div>

      <nav className="flex-1 overflow-y-auto thin-scroll py-4 px-3 space-y-5" aria-label="Main">
        {NAV_GROUPS.map((group) => {
          const items = group.items.filter((i) => canSee(i, role));
          if (items.length === 0) return null;
          return (
            <div key={group.label}>
              {collapsed ? (
                <div className="mx-3 mb-2 hairline border-t" aria-hidden />
              ) : (
                <div className="nav-group-label">{group.label}</div>
              )}
              <div className="space-y-0.5">
                {items.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.to === "/"}
                    className={({ isActive }) => `${linkBase} ${isActive ? "nav-active" : "nav-idle"}`}
                    title={collapsed ? item.label : undefined}
                    aria-label={collapsed ? item.label : undefined}
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

      <div className="px-3 py-3 space-y-0.5 shrink-0 hairline border-t">
        {BOTTOM_NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) => `${linkBase} ${isActive ? "nav-soft-active" : "nav-idle"}`}
            title={collapsed ? item.label : undefined}
            aria-label={collapsed ? item.label : undefined}
          >
            <item.icon size={17} strokeWidth={2} className="shrink-0" />
            {!collapsed && <span className="truncate">{item.label}</span>}
          </NavLink>
        ))}
        <button
          onClick={() => setCollapsed(!collapsed)}
          className={`${linkBase} nav-idle w-full mt-1`}
          title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-expanded={!collapsed}
        >
          {collapsed ? <ChevronsRight size={17} /> : <ChevronsLeft size={17} />}
          {!collapsed && <span>Collapse</span>}
        </button>
      </div>
    </aside>
  );
}
