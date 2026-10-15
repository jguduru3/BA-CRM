import { Monitor, Moon, Sun } from "lucide-react";
import { useThemeStore } from "@/lib/theme";

const OPTIONS = [
  { mode: "light" as const, icon: Sun, label: "Light" },
  { mode: "dark" as const, icon: Moon, label: "Dark" },
  { mode: "system" as const, icon: Monitor, label: "System" },
];

export function ThemeToggle() {
  const mode = useThemeStore((s) => s.mode);
  const setMode = useThemeStore((s) => s.setMode);

  return (
    <div className="hidden sm:inline-flex items-center gap-0.5 p-0.5 rounded-lg glass-solid">
      {OPTIONS.map((o) => {
        const active = mode === o.mode;
        return (
          <button
            key={o.mode}
            onClick={() => setMode(o.mode)}
            className="h-7 w-7 inline-flex items-center justify-center rounded-md transition-all"
            style={{
              backgroundColor: active ? "rgb(var(--ring-brand) / 0.14)" : undefined,
              color: active ? "var(--brand-600)" : "var(--ink-muted)",
            }}
            title={o.label}
            aria-label={o.label}
          >
            <o.icon size={14} />
          </button>
        );
      })}
    </div>
  );
}
