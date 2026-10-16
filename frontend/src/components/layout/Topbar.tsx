import { Search } from "lucide-react";
import { useEffect, useState } from "react";
import { Logo } from "@/icons/Logo";
import { CommandPalette } from "./CommandPalette";
import { MobileNavButton } from "./MobileNav";
import { NotificationsPanel } from "./NotificationsPanel";
import { ThemeToggle } from "./ThemeToggle";
import { UserMenu } from "./UserMenu";

export function Topbar() {
  const [paletteOpen, setPaletteOpen] = useState(false);
  const isMac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPaletteOpen((o) => !o);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  return (
    <header
      className="shrink-0 flex items-center justify-between gap-2 sm:gap-3 px-3.5 sm:px-4 lg:px-6 relative z-30"
      style={{ height: "calc(3.5rem + env(safe-area-inset-top, 0px))", paddingTop: "env(safe-area-inset-top, 0px)" }}
    >
      <div className="flex items-center gap-2 sm:gap-3 min-w-0 flex-1">
        <MobileNavButton />
        <Logo height={24} className="lg:hidden block shrink-0" />
        <button
          onClick={() => setPaletteOpen(true)}
          className="group flex items-center justify-center sm:justify-start gap-2.5 h-9 w-9 sm:w-full max-w-sm rounded-[11px] sm:px-3 text-[13px] transition-all glass hover:border-[rgb(var(--ring-brand)/0.35)] focus-brand ml-auto sm:ml-0"
          style={{ color: "var(--ink-muted)" }}
          aria-label="Search"
        >
          <Search size={16} className="shrink-0" />
          <span className="truncate hidden sm:inline">Search or jump to…</span>
          <kbd
            className="ml-auto hidden md:inline text-[10px] font-semibold rounded-md px-1.5 py-0.5"
            style={{ backgroundColor: "rgb(var(--panel-border) / 0.07)" }}
          >
            {isMac ? "⌘" : "Ctrl"} K
          </kbd>
        </button>
      </div>
      <div className="flex items-center gap-1 sm:gap-2 shrink-0">
        <ThemeToggle />
        <NotificationsPanel />
        <div className="hidden sm:block w-px h-6 mx-1 hairline border-l" />
        <UserMenu />
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
    </header>
  );
}
