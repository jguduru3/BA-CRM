import { Search } from "lucide-react";
import { useEffect, useState } from "react";
import { CommandPalette } from "./CommandPalette";
import { MobileNav } from "./MobileNav";
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
    <header className="h-16 shrink-0 flex items-center justify-between gap-3 px-4 sm:px-6 glass border-b hairline">
      <div className="flex items-center gap-3 min-w-0 flex-1">
        <MobileNav />
        <button
          onClick={() => setPaletteOpen(true)}
          className="group flex items-center gap-2.5 h-10 w-full max-w-md rounded-xl px-3.5 text-sm transition-all glass-solid hover:border-[rgb(var(--ring-brand)/0.35)]"
          style={{ color: "var(--ink-muted)" }}
          aria-label="Search"
        >
          <Search size={16} />
          <span className="truncate hidden sm:inline">Search or jump to…</span>
          <kbd className="ml-auto hidden md:inline text-[10px] font-semibold rounded-md px-1.5 py-0.5" style={{ backgroundColor: "rgb(var(--panel-border) / 0.07)" }}>
            {isMac ? "⌘" : "Ctrl"} K
          </kbd>
        </button>
      </div>
      <div className="flex items-center gap-1.5 sm:gap-2">
        <ThemeToggle />
        <NotificationsPanel />
        <div className="w-px h-6 mx-1 hairline border-l" />
        <UserMenu />
      </div>
      <CommandPalette open={paletteOpen} onClose={() => setPaletteOpen(false)} />
    </header>
  );
}
