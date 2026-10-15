export function Tabs<T extends string>({
  value,
  onChange,
  tabs,
}: {
  value: T;
  onChange: (v: T) => void;
  tabs: { value: T; label: string; count?: number }[];
}) {
  return (
    <div className="inline-flex items-center gap-1 p-1 rounded-xl glass-solid">
      {tabs.map((t) => {
        const active = t.value === value;
        return (
          <button
            key={t.value}
            onClick={() => onChange(t.value)}
            className="relative px-3 py-1.5 rounded-lg text-sm font-medium transition-all duration-150"
            style={{
              color: active ? "white" : "var(--ink-secondary)",
              backgroundImage: active ? "var(--brand-gradient)" : undefined,
            }}
          >
            {t.label}
            {t.count != null && (
              <span
                className="ml-1.5 text-[10px] font-bold px-1.5 py-0.5 rounded-full"
                style={{
                  backgroundColor: active ? "rgb(255 255 255 / 0.25)" : "rgb(var(--panel-border) / 0.1)",
                  color: active ? "white" : "var(--ink-muted)",
                }}
              >
                {t.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
