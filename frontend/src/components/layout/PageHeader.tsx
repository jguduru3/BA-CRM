export function PageHeader({
  title,
  description,
  actions,
  icon,
}: {
  title: string;
  description?: string;
  actions?: React.ReactNode;
  icon?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 mb-6">
      <div className="flex items-center gap-3 min-w-0">
        {icon && (
          <div
            className="hidden sm:flex items-center justify-center h-11 w-11 rounded-2xl shrink-0"
            style={{ backgroundColor: "rgb(var(--ring-brand) / 0.12)", color: "var(--brand-600)" }}
          >
            {icon}
          </div>
        )}
        <div className="min-w-0">
          <h1 className="text-xl font-bold tracking-tight truncate" style={{ color: "var(--ink-primary)" }}>
            {title}
          </h1>
          {description && (
            <p className="text-sm mt-0.5" style={{ color: "var(--ink-muted)" }}>
              {description}
            </p>
          )}
        </div>
      </div>
      {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
    </div>
  );
}
