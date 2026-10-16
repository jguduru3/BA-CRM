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
    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2.5 sm:gap-3 mb-4">
      <div className="flex items-center gap-3 min-w-0">
        {icon && (
          <div
            className="hidden sm:flex items-center justify-center h-9 w-9 rounded-[11px] shrink-0 glass [&_svg]:h-[17px] [&_svg]:w-[17px]"
            style={{ color: "var(--brand-600)", boxShadow: "inset 0 1px 0 var(--glass-highlight), var(--shadow-card)" }}
          >
            {icon}
          </div>
        )}
        <div className="min-w-0">
          <h1 className="text-[20px] leading-tight font-semibold tracking-[-0.02em] truncate" style={{ color: "var(--ink-primary)" }}>
            {title}
          </h1>
          {description && (
            <p className="text-[13px] mt-0.5" style={{ color: "var(--ink-muted)" }}>
              {description}
            </p>
          )}
        </div>
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2 sm:shrink-0">{actions}</div>}
    </div>
  );
}
