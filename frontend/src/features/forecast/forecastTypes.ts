import type { ForecastCategoryKey } from "@/features/opportunities/pipelineTypes";

export type ForecastPeriod = "month" | "quarter";
export type ForecastGroupBy = "none" | "owner" | "team";

/** Amounts are null when the reader's role may not see deal values. */
export interface ForecastCell {
  count: number;
  value: number | null;
  weighted: number | null;
}

export interface ForecastFigures {
  totals: Record<ForecastCategoryKey, ForecastCell>;
  total: ForecastCell;
  /** A category plus every higher one (total value). */
  cumulative: Record<ForecastCategoryKey, number | null>;
  cumulativeWeighted: Record<ForecastCategoryKey, number | null>;
  commitAndWon: number | null;
  closedWon: number | null;
  quota: number | null;
  /** (Commit + Closed won) / quota, in percent. */
  attainment: number | null;
  wonAttainment: number | null;
}

export interface ForecastGroup extends ForecastFigures {
  id: number | null;
  name: string;
  initials?: string | null;
  color?: string | null;
}

export interface ForecastBucket extends ForecastFigures {
  start: string;
  end: string;
  label: string;
  groups: ForecastGroup[];
}

export interface ForecastResponse {
  period: ForecastPeriod;
  start: string;
  periods: number;
  groupBy: ForecastGroupBy;
  valuesVisible: boolean;
  categories: { key: ForecastCategoryKey; label: string; order: number }[];
  buckets: ForecastBucket[];
}

/** Snap a date to the first day of its month / quarter (local, YYYY-MM-DD). */
export function periodStart(period: ForecastPeriod, d: Date): string {
  const month = period === "quarter" ? Math.floor(d.getMonth() / 3) * 3 : d.getMonth();
  return isoDate(new Date(d.getFullYear(), month, 1));
}

export function shiftPeriod(period: ForecastPeriod, start: string, by: number): string {
  const [y, m] = start.split("-").map(Number);
  return isoDate(new Date(y, m - 1 + by * (period === "quarter" ? 3 : 1), 1));
}

export function isoDate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export function periodLabel(period: ForecastPeriod, start: string): string {
  const [y, m] = start.split("-").map(Number);
  if (period === "quarter") return `Q${Math.floor((m - 1) / 3) + 1} ${y}`;
  return new Date(y, m - 1, 1).toLocaleDateString("en-IN", { month: "long", year: "numeric" });
}
