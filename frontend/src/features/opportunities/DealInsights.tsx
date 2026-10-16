import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, History } from "lucide-react";
import { api } from "@/lib/api";
import { formatCompactCurrency, formatDateTime } from "@/lib/format";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { HealthBadge, HealthFactorList, sortedFactors } from "./HealthBadge";
import { categoryLabel, RISK_FLAG_LABELS, type DealRecord, type StageHistoryRow } from "./pipelineTypes";

function daysLabel(seconds: number | null): string | null {
  if (seconds == null) return null;
  const d = seconds / 86_400;
  if (d < 1) return "under a day";
  return `${Math.round(d)} ${Math.round(d) === 1 ? "day" : "days"}`;
}

/** Drawer panel: deal health with its reasons, forecast figures and the stage history. */
export function DealInsights({ deal, userName }: { deal: DealRecord; userName: (id: number | null) => string | undefined }) {
  const history = useQuery({
    queryKey: ["opportunities", deal.id, "stage-history"],
    queryFn: async () => (await api.get<StageHistoryRow[]>(`/opportunities/${deal.id}/stage-history`)).data,
  });
  const flags = (deal.healthFlags ?? "").split(",").filter(Boolean);

  return (
    <div className="mb-5 grid grid-cols-1 gap-3">
      <div className="rounded-xl hairline border p-4">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs font-semibold uppercase tracking-wide" style={{ color: "var(--ink-muted)" }}>
              Deal health
            </p>
            <p className="text-xs mt-0.5" style={{ color: "var(--ink-muted)" }}>
              {deal.healthScore == null ? "Not scored — closed deals have no health score" : "Rules-based score next to the rep's win probability"}
            </p>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {deal.probability != null && (
              <span className="text-xs font-semibold tabular-nums" style={{ color: "var(--ink-secondary)" }} title="Win probability">
                {deal.probability}% win
              </span>
            )}
            <HealthBadge score={deal.healthScore} factors={deal.healthFactors} size="md" />
          </div>
        </div>
        {flags.length > 0 && (
          <div className="flex flex-wrap gap-1.5 mt-3">
            {flags.map((f) => (
              <span key={f} className="badge-critical inline-flex items-center gap-1">
                <AlertTriangle size={11} /> {RISK_FLAG_LABELS[f] ?? f}
              </span>
            ))}
          </div>
        )}
        {deal.healthScore != null && (deal.healthFactors?.length ?? 0) > 0 && (
          <div className="mt-3 pt-3 hairline border-t">
            <HealthFactorList factors={sortedFactors(deal.healthFactors)} />
          </div>
        )}
        <div className="mt-3 pt-3 hairline border-t grid grid-cols-2 gap-3 text-sm">
          <div>
            <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
              Forecast category
            </p>
            <p className="font-semibold" style={{ color: "var(--ink-primary)" }}>
              {categoryLabel(deal.forecastCategory)}
              {deal.forecastCategoryOverridden ? " ✎" : ""}
            </p>
          </div>
          <div>
            <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
              Weighted amount
            </p>
            <p className="font-semibold tabular-nums" style={{ color: "var(--ink-primary)" }}>
              {deal.weightedValue == null ? "—" : formatCompactCurrency(deal.weightedValue)}
            </p>
          </div>
        </div>
      </div>

      <div className="rounded-xl hairline border p-4">
        <p className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide mb-3" style={{ color: "var(--ink-muted)" }}>
          <History size={13} /> Stage history
        </p>
        {history.isError ? (
          <QueryError error={history.error} onRetry={() => history.refetch()} compact />
        ) : history.isLoading || !history.data ? (
          <Skeleton className="h-16" />
        ) : history.data.length === 0 ? (
          <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
            No stage changes recorded yet.
          </p>
        ) : (
          <ol className="space-y-2.5">
            {[...history.data].reverse().map((h) => {
              const spent = daysLabel(h.secondsInFromStage);
              return (
                <li key={h.id} className="flex items-start gap-2.5 text-sm">
                  <span className="mt-1.5 h-2 w-2 rounded-full shrink-0" style={{ backgroundImage: "var(--brand-gradient)" }} />
                  <span className="min-w-0">
                    <span style={{ color: "var(--ink-primary)" }}>
                      {h.fromStage ? (
                        <>
                          {h.fromStage} → <strong>{h.toStage}</strong>
                        </>
                      ) : (
                        <>
                          Created in <strong>{h.toStage}</strong>
                        </>
                      )}
                    </span>
                    <span className="block text-xs" style={{ color: "var(--ink-muted)" }}>
                      {formatDateTime(h.enteredAt)}
                      {userName(h.changedBy) ? ` · ${userName(h.changedBy)}` : ""}
                      {spent && h.fromStage ? ` · ${spent} in ${h.fromStage}` : ""}
                    </span>
                  </span>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </div>
  );
}
