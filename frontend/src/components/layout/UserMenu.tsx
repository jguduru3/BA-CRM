import { LogOut, Settings, UserCircle } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuthStore } from "@/lib/auth";
import { roleLabel } from "@/lib/format";
import { Avatar } from "@/components/ui/Avatar";

export function UserMenu() {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const navigate = useNavigate();

  useEffect(() => {
    function onClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onClick);
    return () => document.removeEventListener("mousedown", onClick);
  }, []);

  if (!user) return null;

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-2 rounded-lg pl-1 pr-2 py-1 transition-colors hover:bg-[rgb(var(--panel-border)/0.06)]"
      >
        <Avatar name={user.name} color={user.color} size={30} />
        <div className="hidden sm:block text-left leading-tight">
          <div className="text-[13px] font-semibold" style={{ color: "var(--ink-primary)" }}>
            {user.name}
          </div>
          <div className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
            {roleLabel(user.role)}
          </div>
        </div>
      </button>
      {open && (
        <div className="absolute right-0 mt-2 w-52 glass-strong glass-reveal rounded-xl shadow-glass-lg z-50 animate-slide-up overflow-hidden py-1.5">
          <div className="px-3.5 py-2 hairline border-b mb-1">
            <div className="text-sm font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
              {user.name}
            </div>
            <div className="text-xs truncate" style={{ color: "var(--ink-muted)" }}>
              {user.email}
            </div>
          </div>
          <button
            className="w-full flex items-center gap-2.5 px-3.5 py-2 text-sm hover:bg-[rgb(var(--panel-border)/0.06)]"
            style={{ color: "var(--ink-secondary)" }}
            onClick={() => {
              setOpen(false);
              navigate("/settings");
            }}
          >
            <UserCircle size={16} /> Profile
          </button>
          <button
            className="w-full flex items-center gap-2.5 px-3.5 py-2 text-sm hover:bg-[rgb(var(--panel-border)/0.06)]"
            style={{ color: "var(--ink-secondary)" }}
            onClick={() => {
              setOpen(false);
              navigate("/settings");
            }}
          >
            <Settings size={16} /> Settings
          </button>
          <button
            className="w-full flex items-center gap-2.5 px-3.5 py-2 text-sm hover:bg-[rgb(var(--panel-border)/0.06)]"
            style={{ color: "var(--critical)" }}
            onClick={() => {
              logout();
              navigate("/login");
            }}
          >
            <LogOut size={16} /> Sign out
          </button>
        </div>
      )}
    </div>
  );
}
