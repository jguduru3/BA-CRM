import type { LucideIcon } from "lucide-react";
import { ArrowDownRight, ArrowUpRight } from "lucide-react";

export function StatTile({
  label,
  value,
  icon: Icon,
  trend,
  tone = "brand",
  sub,
}: {
  label: string;
  value: string | number;
  icon: LucideIcon;
  trend?: { value: number; positiveIsGood?: boolean };
  tone?: "brand" | "good" | "warning" | "critical";
  sub?: string;
}) {
  const toneColor =
    tone === "good" ? "var(--good)" : tone === "warning" ? "var(--warning)" : tone === "critical" ? "var(--critical)" : "var(--brand-500)";
  const toneBg =
    tone === "good"
      ? "rgb(12 163 12 / 0.12)"
      : tone === "warning"
      ? "rgb(201 133 0 / 0.14)"
      : tone === "critical"
      ? "rgb(208 59 59 / 0.12)"
      : "rgb(var(--ring-brand) / 0.12)";

  const isGood = trend ? (trend.positiveIsGood ?? true ? trend.value >= 0 : trend.value < 0) : null;

  return (
    <div className="glass-card-hover p-3 sm:p-4 min-w-0">
      <div className="flex items-start justify-between">
        <span className="text-[11px] font-semibold uppercase tracking-[0.04em] leading-tight pr-2" style={{ color: "var(--ink-muted)" }}>
          {label}
        </span>
        <div
          className="flex items-center justify-center h-7 w-7 sm:h-8 sm:w-8 rounded-[9px] shrink-0"
          style={{ backgroundColor: toneBg }}
        >
          <Icon size={15} style={{ color: toneColor }} strokeWidth={2} />
        </div>
      </div>
      <div className="mt-1.5 sm:mt-2 text-[21px] sm:text-[25px] font-semibold tabular-nums tracking-[-0.025em] truncate" style={{ color: "var(--ink-primary)" }}>
        {value}
      </div>
      <div className="mt-0.5 sm:mt-1 flex items-center gap-1.5 text-[11.5px] min-h-[16px] truncate">
        {trend && (
          <span
            className="inline-flex items-center gap-0.5 font-semibold"
            style={{ color: isGood ? "var(--good)" : "var(--critical)" }}
          >
            {trend.value >= 0 ? <ArrowUpRight size={13} /> : <ArrowDownRight size={13} />}
            {Math.abs(trend.value)}%
          </span>
        )}
        {sub && <span style={{ color: "var(--ink-muted)" }}>{sub}</span>}
      </div>
    </div>
  );
}
