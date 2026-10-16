import { X } from "lucide-react";
import { useEffect } from "react";
import { createPortal } from "react-dom";

export function Drawer({
  open,
  onClose,
  title,
  subtitle,
  children,
  headerExtra,
  width = "30rem",
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  subtitle?: React.ReactNode;
  children: React.ReactNode;
  headerExtra?: React.ReactNode;
  width?: string;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [open, onClose]);

  if (!open) return null;

  return createPortal(
    <div className="fixed inset-0 z-[70]">
      <div className="fixed inset-0 bg-slate-950/25 backdrop-blur-[3px] animate-fade-in" onClick={onClose} aria-hidden />
      <div
        role="dialog"
        aria-modal="true"
        className="fixed top-0 right-0 bottom-0 sm:top-2.5 sm:right-2.5 sm:bottom-2.5 glass-strong glass-reveal animate-slide-in-right flex flex-col sm:rounded-[22px] overflow-hidden"
        style={{ width, maxWidth: "calc(100vw - 0px)" }}
      >
        <div className="flex items-start justify-between gap-3 px-4 py-3 hairline border-b shrink-0">
          <div className="min-w-0">
            <h2 className="text-[15px] font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
              {title}
            </h2>
            {subtitle && <div className="mt-0.5 text-xs" style={{ color: "var(--ink-muted)" }}>{subtitle}</div>}
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            {headerExtra}
            <button type="button" onClick={onClose} className="btn-icon" aria-label="Close">
              <X size={17} />
            </button>
          </div>
        </div>
        <div className="flex-1 overflow-y-auto thin-scroll px-4 py-4">{children}</div>
      </div>
    </div>,
    document.body
  );
}
