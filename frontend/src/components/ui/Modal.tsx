import { X } from "lucide-react";
import { useEffect } from "react";
import { createPortal } from "react-dom";

export function Modal({
  open,
  onClose,
  title,
  children,
  footer,
  width = "32rem",
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
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
    <div className="fixed inset-0 z-[70] flex items-start sm:items-center justify-center p-4 overflow-y-auto">
      <div
        className="fixed inset-0 bg-slate-950/40 backdrop-blur-sm animate-fade-in"
        onClick={onClose}
        aria-hidden
      />
      <div
        role="dialog"
        aria-modal="true"
        className="relative w-full glass-strong glass-reveal rounded-2xl shadow-glass-lg animate-slide-up my-8"
        style={{ maxWidth: width }}
      >
        <div className="flex items-center justify-between px-5 py-4 hairline border-b">
          <h2 className="text-[15px] font-semibold" style={{ color: "var(--ink-primary)" }}>
            {title}
          </h2>
          <button type="button" onClick={onClose} className="btn-icon" aria-label="Close">
            <X size={17} />
          </button>
        </div>
        <div className="px-5 py-5 max-h-[70vh] overflow-y-auto thin-scroll">{children}</div>
        {footer && <div className="flex items-center justify-end gap-2 px-5 py-4 hairline border-t">{footer}</div>}
      </div>
    </div>,
    document.body
  );
}
