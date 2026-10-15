import { AlertCircle, CheckCircle2, Info, X } from "lucide-react";
import { useToastStore } from "@/lib/toast";

const ICONS = { success: CheckCircle2, error: AlertCircle, info: Info };
const COLORS = { success: "var(--good)", error: "var(--critical)", info: "var(--brand-500)" };

export function Toaster() {
  const items = useToastStore((s) => s.items);
  const dismiss = useToastStore((s) => s.dismiss);

  return (
    <div className="fixed bottom-5 right-5 z-[100] flex flex-col gap-2 w-80 max-w-[calc(100vw-2.5rem)]">
      {items.map((t) => {
        const Icon = ICONS[t.kind];
        return (
          <div
            key={t.id}
            className="glass-strong glass-reveal rounded-xl shadow-glass-lg px-3.5 py-3 flex items-start gap-2.5 animate-slide-up"
          >
            <Icon size={18} style={{ color: COLORS[t.kind] }} className="shrink-0 mt-0.5" />
            <p className="text-sm flex-1" style={{ color: "var(--ink-primary)" }}>
              {t.message}
            </p>
            <button className="btn-icon h-6 w-6 -mt-0.5 -mr-1" onClick={() => dismiss(t.id)} aria-label="Dismiss">
              <X size={14} />
            </button>
          </div>
        );
      })}
    </div>
  );
}
