// Semantic color mapping for recurring stage/status/priority strings across
// the app — kept in one place so "Won" always reads the same shade of green
// whether it's a Lead stage or an Opportunity stage.
const POSITIVE = new Set(["won", "active", "done", "completed", "paid", "agreed", "customer", "approved"]);
const NEGATIVE = new Set(["lost", "cancelled", "churned", "overdue", "rejected", "blocked"]);
const WARNING = new Set([
  "pending",
  "in progress",
  "negotiation",
  "proposal",
  "requested",
  "payment pending",
  "on_hold",
  "on hold",
]);

export function stageTone(raw: string | null | undefined): "good" | "critical" | "warning" | "brand" | "neutral" {
  const v = (raw || "").toLowerCase();
  if (POSITIVE.has(v)) return "good";
  if (NEGATIVE.has(v)) return "critical";
  if (WARNING.has(v)) return "warning";
  if (v === "new" || v === "todo" || v === "prospect" || v === "scheduled") return "brand";
  return "neutral";
}

const TONE_CLASS: Record<string, string> = {
  good: "badge-good",
  critical: "badge-critical",
  warning: "badge-warning",
  brand: "badge-brand",
  neutral: "badge-neutral",
};

export function StageBadge({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="badge-neutral">—</span>;
  return <span className={TONE_CLASS[stageTone(value)]}>{value}</span>;
}

export function PriorityBadge({ value }: { value: string | null | undefined }) {
  const v = (value || "").toLowerCase();
  const tone = v === "high" || v === "urgent" ? "critical" : v === "low" ? "neutral" : "warning";
  return <span className={TONE_CLASS[tone]}>{value || "—"}</span>;
}

export function Dot({ tone = "neutral" as const }: { tone?: "good" | "critical" | "warning" | "brand" | "neutral" }) {
  const color =
    tone === "good"
      ? "var(--good)"
      : tone === "critical"
      ? "var(--critical)"
      : tone === "warning"
      ? "var(--warning)"
      : tone === "brand"
      ? "var(--brand-500)"
      : "var(--ink-muted)";
  return <span className="inline-block h-1.5 w-1.5 rounded-full" style={{ backgroundColor: color }} />;
}
