// Pipeline-intelligence fields (forecast categories, deal health, stage history),
// kept feature-local so lib/types.ts and features/crm/types.ts stay untouched.
import type { OpportunityRecord } from "@/features/crm/types";

export type ForecastCategoryKey = "NOT_FORECASTED" | "PIPELINE" | "BEST_CASE" | "COMMIT" | "CLOSED_WON";

/** Ordered lowest → highest; a category's meaning comes from its position. */
export const FORECAST_CATEGORIES: { key: ForecastCategoryKey; label: string }[] = [
  { key: "NOT_FORECASTED", label: "Not forecasted" },
  { key: "PIPELINE", label: "Pipeline" },
  { key: "BEST_CASE", label: "Best case" },
  { key: "COMMIT", label: "Commit" },
  { key: "CLOSED_WON", label: "Closed won" },
];

export const categoryLabel = (key: string | null | undefined) =>
  FORECAST_CATEGORIES.find((c) => c.key === key)?.label ?? "—";

export interface HealthFactor {
  key: string;
  label: string;
  /** 0 = fine; negative = points deducted from 100. */
  points: number;
  detail: string;
}

export type DealRecord = OpportunityRecord & {
  forecastCategory?: ForecastCategoryKey | null;
  forecastCategoryOverridden?: boolean;
  closeDateSlips?: number;
  stageEnteredAt?: string | null;
  /** 0–100 for open deals, null for closed ones. */
  healthScore?: number | null;
  healthFactors?: HealthFactor[] | null;
  /** Comma-separated raised risk flags. */
  healthFlags?: string | null;
  healthComputedAt?: string | null;
  /** value × probability / 100 (null when masked). */
  weightedValue?: number | null;
};

export interface StageHistoryRow {
  id: number;
  opportunityId: number;
  fromStage: string | null;
  toStage: string;
  changedBy: number | null;
  enteredAt: string;
  secondsInFromStage: number | null;
  secondsSinceCreated: number | null;
}

export const RISK_FLAG_LABELS: Record<string, string> = {
  STALLED_STAGE: "Stalled stage",
  SLIPPED_CLOSE_DATE: "Close date slipping",
  NO_ACTIVITY: "No activity in 14 days",
  NO_CONTACT: "No contact",
};

export function healthTone(score: number | null | undefined): "good" | "warning" | "critical" | "neutral" {
  if (score == null) return "neutral";
  if (score >= 70) return "good";
  if (score >= 40) return "warning";
  return "critical";
}
