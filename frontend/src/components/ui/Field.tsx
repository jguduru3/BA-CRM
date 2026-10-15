export function Field({
  label,
  children,
  hint,
  required,
  className = "",
}: {
  label: string;
  children: React.ReactNode;
  hint?: string;
  required?: boolean;
  className?: string;
}) {
  return (
    <div className={className}>
      <label className="field-label">
        {label}
        {required && <span style={{ color: "var(--critical)" }}> *</span>}
      </label>
      {children}
      {hint && (
        <p className="mt-1 text-xs" style={{ color: "var(--ink-muted)" }}>
          {hint}
        </p>
      )}
    </div>
  );
}

export function FieldGrid({ children }: { children: React.ReactNode }) {
  return <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">{children}</div>;
}

export function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="text-xs font-bold uppercase tracking-wide pt-1 pb-0.5" style={{ color: "var(--brand-600)" }}>
      {children}
    </h3>
  );
}
