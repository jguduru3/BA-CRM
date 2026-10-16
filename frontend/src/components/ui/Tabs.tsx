/** iOS-style segmented control: a soft track with a raised thumb on the active item. */
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
    <div className="segmented" role="tablist">
      {tabs.map((t) => {
        const active = t.value === value;
        return (
          <button
            key={t.value}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(t.value)}
            className={`segmented-item ${active ? "is-active" : ""}`}
          >
            {t.label}
            {t.count != null && <span className="segmented-count">{t.count}</span>}
          </button>
        );
      })}
    </div>
  );
}
