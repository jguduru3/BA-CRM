import type { LucideIcon } from "lucide-react";
import { Inbox } from "lucide-react";

export function EmptyState({
  icon: Icon = Inbox,
  title,
  description,
  action,
}: {
  icon?: LucideIcon;
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center text-center py-16 px-6">
      <div
        className="flex items-center justify-center h-14 w-14 rounded-2xl mb-4"
        style={{ backgroundColor: "rgb(var(--ring-brand) / 0.1)" }}
      >
        <Icon size={24} style={{ color: "var(--brand-500)" }} strokeWidth={1.75} />
      </div>
      <h3 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
        {title}
      </h3>
      {description && (
        <p className="mt-1.5 text-sm max-w-sm" style={{ color: "var(--ink-muted)" }}>
          {description}
        </p>
      )}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
