import { HeartPulse } from "lucide-react";
import { useId, useState } from "react";
import { healthTone, type HealthFactor } from "./pipelineTypes";

const TONE_COLOR: Record<string, string> = {
  good: "var(--good)",
  warning: "var(--warning)",
  critical: "var(--critical)",
  neutral: "var(--ink-muted)",
};
const TONE_BG: Record<string, string> = {
  good: "rgb(12 163 12 / 0.12)",
  warning: "rgb(201 133 0 / 0.14)",
  critical: "rgb(208 59 59 / 0.12)",
  neutral: "rgb(var(--panel-border) / 0.08)",
};

/** "Why" lines: deductions first (largest first), then what is fine. */
export function sortedFactors(factors: HealthFactor[] | null | undefined): HealthFactor[] {
  return [...(factors ?? [])].sort((a, b) => a.points - b.points);
}

/**
 * Deal health score badge (0–100). Hover or focus shows the explaining
 * factors in a small popover, so the score is never a black box.
 */
export function HealthBadge({
  score,
  factors,
  size = "sm",
}: {
  score: number | null | undefined;
  factors?: HealthFactor[] | null;
  size?: "sm" | "md";
}) {
  const [open, setOpen] = useState(false);
  const id = useId();
  if (score == null) return null;
  const tone = healthTone(score);
  const list = sortedFactors(factors);
  const summary = list
    .filter((f) => f.points < 0)
    .map((f) => `${f.points} ${f.detail}`)
    .join("\n");

  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      <span
        tabIndex={0}
        role="img"
        aria-label={`Deal health ${score} of 100${summary ? `. ${summary}` : ""}`}
        aria-describedby={open ? id : undefined}
        className={`inline-flex items-center gap-1 rounded-full font-bold tabular-nums outline-none focus-visible:ring-2 ${
          size === "md" ? "text-xs px-2 py-1" : "text-[11px] px-1.5 py-0.5"
        }`}
        style={{ backgroundColor: TONE_BG[tone], color: TONE_COLOR[tone] }}
      >
        <HeartPulse size={size === "md" ? 13 : 11} strokeWidth={2.4} />
        {score}
      </span>
      {open && list.length > 0 && (
        <span
          id={id}
          role="tooltip"
          className="glass-strong shadow-glass-lg absolute z-50 right-0 top-full mt-1.5 w-64 rounded-lg px-3 py-2.5 text-left"
          onClick={(e) => e.stopPropagation()}
        >
          <span className="block text-xs font-semibold mb-1.5" style={{ color: "var(--ink-primary)" }}>
            Deal health {score}/100
          </span>
          <HealthFactorList factors={list} compact />
        </span>
      )}
    </span>
  );
}

export function HealthFactorList({ factors, compact }: { factors: HealthFactor[]; compact?: boolean }) {
  return (
    <span className={`flex flex-col ${compact ? "gap-1" : "gap-2"}`}>
      {factors.map((f) => (
        <span key={f.key} className={`flex items-start justify-between gap-3 ${compact ? "text-[11px]" : "text-sm"}`}>
          <span className="min-w-0" style={{ color: "var(--ink-secondary)" }}>
            {!compact && (
              <span className="block font-medium" style={{ color: "var(--ink-primary)" }}>
                {f.label}
              </span>
            )}
            {f.detail}
          </span>
          <span
            className="shrink-0 font-semibold tabular-nums"
            style={{ color: f.points < 0 ? (f.points <= -20 ? "var(--critical)" : "var(--warning)") : "var(--good)" }}
          >
            {f.points < 0 ? f.points : "✓"}
          </span>
        </span>
      ))}
    </span>
  );
}
