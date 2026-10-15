import type { CampaignProgress } from "./campaignTypes";

/** Stacked progress: delivered/read (green), sent (brand), failed (red), skipped (amber), remaining (track). */
export function CampaignProgressBar({ progress, compact = false }: { progress: CampaignProgress; compact?: boolean }) {
  const c = progress.counts ?? {};
  const total = Math.max(progress.total, 0);
  const delivered = (c.delivered ?? 0) + (c.read ?? 0);
  const sent = c.sent ?? 0;
  const failed = c.failed ?? 0;
  const skipped = (c.skipped_opted_out ?? 0) + (c.skipped ?? 0);
  const pct = (n: number) => (total === 0 ? 0 : (n / total) * 100);
  const segments = [
    { key: "delivered", value: delivered, color: "var(--good)", label: "Delivered / read" },
    { key: "sent", value: sent, color: "var(--brand-500)", label: "Sent" },
    { key: "failed", value: failed, color: "var(--critical)", label: "Failed" },
    { key: "skipped", value: skipped, color: "var(--warning)", label: "Skipped (opted out)" },
  ];

  return (
    <div className="w-full">
      <div
        className={`flex w-full overflow-hidden rounded-full ${compact ? "h-1.5" : "h-2.5"}`}
        style={{ backgroundColor: "rgb(var(--panel-border) / 0.12)" }}
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(progress.percent)}
        aria-label="Campaign progress"
      >
        {segments.map((s) =>
          s.value > 0 ? (
            <div
              key={s.key}
              title={`${s.label}: ${s.value}`}
              className="h-full transition-[width] duration-700 ease-out"
              style={{ width: `${pct(s.value)}%`, backgroundColor: s.color }}
            />
          ) : null
        )}
      </div>
      {!compact && (
        <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px]" style={{ color: "var(--ink-muted)" }}>
          {segments.map((s) => (
            <span key={s.key} className="inline-flex items-center gap-1.5">
              <span className="h-2 w-2 rounded-full" style={{ backgroundColor: s.color }} />
              {s.label} <strong className="tabular-nums" style={{ color: "var(--ink-secondary)" }}>{s.value}</strong>
            </span>
          ))}
          <span className="inline-flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full" style={{ backgroundColor: "rgb(var(--panel-border) / 0.3)" }} />
            Waiting <strong className="tabular-nums" style={{ color: "var(--ink-secondary)" }}>{(c.pending ?? 0) + (c.sending ?? 0)}</strong>
          </span>
        </div>
      )}
    </div>
  );
}
