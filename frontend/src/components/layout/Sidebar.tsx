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

  const linkBase = `group relative flex items-center gap-2.5 rounded-[10px] h-8 text-[13.5px] font-medium transition-all duration-150 ${
    collapsed ? "justify-center px-0 w-10 mx-auto" : "px-2.5"
  }`;

  return (
    <aside
      className={`hidden lg:flex flex-col shrink-0 my-2.5 ml-2.5 sidebar-panel transition-[width] duration-200 ${
        collapsed ? "w-[64px]" : "w-[228px]"
      }`}
      style={{ height: "calc(100% - 1.25rem)" }}
      aria-label="Sidebar"
    >
      <div className={`h-[60px] flex items-center shrink-0 ${collapsed ? "justify-center px-2" : "px-4 pt-1"}`}>
        {collapsed ? <Logo height={18} /> : <Logo height={28} />}
      </div>

      <nav className="flex-1 overflow-y-auto thin-scroll pb-3 pt-1 px-2 space-y-3.5" aria-label="Main">
        {NAV_GROUPS.map((group) => {
          const items = group.items.filter((i) => canSee(i, role));
          if (items.length === 0) return null;
          return (
            <div key={group.label}>
              {collapsed ? (
                <div className="mx-2 mb-1.5 hairline border-t" aria-hidden />
              ) : (
                <div className="nav-group-label">{group.label}</div>
              )}
              <div className="space-y-px">
                {items.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.to === "/"}
                    className={({ isActive }) => `${linkBase} ${isActive ? "nav-active" : "nav-idle"}`}
                    title={collapsed ? item.label : undefined}
                    aria-label={collapsed ? item.label : undefined}
                  >
                    <item.icon size={16} strokeWidth={1.9} className="shrink-0" />
                    {!collapsed && <span className="truncate">{item.label}</span>}
                  </NavLink>
                ))}
              </div>
            </div>
          );
        })}
      </nav>

      <div className="px-2 py-2 space-y-px shrink-0 hairline border-t">
        {BOTTOM_NAV.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) => `${linkBase} ${isActive ? "nav-soft-active" : "nav-idle"}`}
            title={collapsed ? item.label : undefined}
            aria-label={collapsed ? item.label : undefined}
          >
            <item.icon size={16} strokeWidth={1.9} className="shrink-0" />
            {!collapsed && <span className="truncate">{item.label}</span>}
          </NavLink>
        ))}
        <button
          onClick={() => setCollapsed(!collapsed)}
          className={`${linkBase} nav-idle ${collapsed ? "" : "w-full"} mt-0.5`}
          title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          aria-expanded={!collapsed}
        >
          {collapsed ? <ChevronsRight size={16} /> : <ChevronsLeft size={16} />}
          {!collapsed && <span>Collapse</span>}
        </button>
      </div>
    </aside>
  );
}
