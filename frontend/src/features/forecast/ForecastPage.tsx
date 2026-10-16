import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, Gauge, Pencil, Scale, Target, Trophy } from "lucide-react";
import { useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { useChartPalette } from "@/lib/chartColors";
import { formatCompactCurrency } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { StatTile } from "@/components/ui/StatTile";
import { Tabs } from "@/components/ui/Tabs";
import { ChartTooltip } from "@/components/charts/ChartTooltip";
import { FORECAST_CATEGORIES, type ForecastCategoryKey } from "@/features/opportunities/pipelineTypes";
import {
  periodLabel,
  periodStart,
  shiftPeriod,
  type ForecastBucket,
  type ForecastFigures,
  type ForecastGroup,
  type ForecastGroupBy,
  type ForecastPeriod,
  type ForecastResponse,
} from "./forecastTypes";

/** Ordered categories shown as columns (Not forecasted is listed last, muted). */
const SHOWN: ForecastCategoryKey[] = ["PIPELINE", "BEST_CASE", "COMMIT", "CLOSED_WON", "NOT_FORECASTED"];
const TREND_PERIODS = 3;

function useCategoryColors() {
  const p = useChartPalette();
  return {
    NOT_FORECASTED: p.axis,
    PIPELINE: p.series[0],
    BEST_CASE: p.series[3],
    COMMIT: p.series[1],
    CLOSED_WON: p.series[2],
  } as Record<ForecastCategoryKey, string>;
}

const money = (v: number | null | undefined) => (v == null ? "—" : formatCompactCurrency(v));
const label = (k: ForecastCategoryKey) => FORECAST_CATEGORIES.find((c) => c.key === k)?.label ?? k;

export default function ForecastPage() {
  const role = useAuthStore((s) => s.user?.role);
  const manager = isManagerUp(role);
  const readOnly = isReadOnly(role);
  const [period, setPeriod] = useState<ForecastPeriod>("quarter");
  const [start, setStart] = useState(() => periodStart("quarter", new Date()));
  const [groupBy, setGroupBy] = useState<ForecastGroupBy>("owner");
  const effectiveGroup: ForecastGroupBy = manager ? groupBy : "none";

  const forecast = useQuery({
    queryKey: ["forecast", period, start, effectiveGroup],
    queryFn: async () =>
      (
        await api.get<ForecastResponse>("/forecast", {
          params: { period, start, periods: TREND_PERIODS, groupBy: effectiveGroup },
        })
      ).data,
  });

  const changePeriod = (p: ForecastPeriod) => {
    setPeriod(p);
    setStart(periodStart(p, new Date()));
  };

  const bucket = forecast.data?.buckets[0];
  const valuesVisible = forecast.data?.valuesVisible ?? true;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Forecast"
        description="Weighted pipeline by forecast category, against quota."
        icon={<Gauge size={20} />}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Tabs
              value={period}
              onChange={changePeriod}
              tabs={[
                { value: "month", label: "Month" },
                { value: "quarter", label: "Quarter" },
              ]}
            />
            <div className="inline-flex items-center gap-1 p-1 rounded-xl glass-solid">
              <button className="btn-icon" aria-label="Previous period" onClick={() => setStart((s) => shiftPeriod(period, s, -1))}>
                <ChevronLeft size={16} />
              </button>
              <span className="text-sm font-semibold tabular-nums px-1.5 min-w-[7.5rem] text-center" style={{ color: "var(--ink-primary)" }}>
                {periodLabel(period, start)}
              </span>
              <button className="btn-icon" aria-label="Next period" onClick={() => setStart((s) => shiftPeriod(period, s, 1))}>
                <ChevronRight size={16} />
              </button>
            </div>
          </div>
        }
      />

      {!valuesVisible && (
        <p className="glass-card px-4 py-3 text-sm" style={{ color: "var(--ink-secondary)" }}>
          Deal values are hidden for your role — the forecast shows deal counts only.
        </p>
      )}

      {forecast.isError ? (
        <div className="glass-card">
          <QueryError error={forecast.error} onRetry={() => forecast.refetch()} title="Couldn't load the forecast" />
        </div>
      ) : forecast.isLoading || !bucket ? (
        <>
          <div className="grid grid-cols-2 xl:grid-cols-4 gap-3 sm:gap-4">
            {Array.from({ length: 4 }).map((_, i) => (
              <Skeleton key={i} className="h-[96px] sm:h-[118px] rounded-2xl" />
            ))}
          </div>
          <Skeleton className="h-40 rounded-2xl" />
        </>
      ) : (
        <>
          <HeadlineTiles bucket={bucket} valuesVisible={valuesVisible} />
          <CategoryColumns bucket={bucket} valuesVisible={valuesVisible} />
          <div className="grid grid-cols-1 xl:grid-cols-5 gap-4">
            <div className="glass-card p-5 xl:col-span-3">
              <h3 className="text-sm font-semibold mb-1" style={{ color: "var(--ink-primary)" }}>
                Next {TREND_PERIODS} {period === "quarter" ? "quarters" : "months"} by category
              </h3>
              <p className="text-xs mb-4" style={{ color: "var(--ink-muted)" }}>
                {valuesVisible ? "Weighted amount (value × probability) by expected close date" : "Deal count by expected close date"}
              </p>
              <TrendChart buckets={forecast.data!.buckets} valuesVisible={valuesVisible} />
            </div>
            <div className="glass-card p-5 xl:col-span-2">
              <QuotaPanel bucket={bucket} valuesVisible={valuesVisible} />
            </div>
          </div>
          {manager && (
            <div className="glass-card p-0 overflow-hidden">
              <div className="flex flex-wrap items-center justify-between gap-3 px-5 pt-4 pb-3">
                <div>
                  <h3 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                    By {groupBy === "team" ? "team" : "owner"}
                  </h3>
                  <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                    {periodLabel(period, start)} · deals closing in the period
                  </p>
                </div>
                <Tabs
                  value={groupBy === "none" ? "owner" : groupBy}
                  onChange={(v) => setGroupBy(v)}
                  tabs={[
                    { value: "owner", label: "Owners" },
                    { value: "team", label: "Teams" },
                  ]}
                />
              </div>
              <GroupTable
                groups={bucket.groups}
                valuesVisible={valuesVisible}
                editableQuota={groupBy === "owner" && !readOnly}
                period={period}
                periodStartDate={bucket.start}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}

function HeadlineTiles({ bucket, valuesVisible }: { bucket: ForecastBucket; valuesVisible: boolean }) {
  const t = bucket.totals;
  const openWeighted =
    valuesVisible ? (t.PIPELINE.weighted ?? 0) + (t.BEST_CASE.weighted ?? 0) + (t.COMMIT.weighted ?? 0) : null;
  const openCount = t.PIPELINE.count + t.BEST_CASE.count + t.COMMIT.count;
  return (
    <div className="grid grid-cols-2 xl:grid-cols-4 gap-3 sm:gap-4">
      <StatTile
        label="Commit + Closed won"
        value={valuesVisible ? money(bucket.commitAndWon) : String(t.COMMIT.count + t.CLOSED_WON.count)}
        icon={Target}
        tone="brand"
        sub={valuesVisible ? `${t.COMMIT.count + t.CLOSED_WON.count} deals` : "deals"}
      />
      <StatTile
        label="Weighted open pipeline"
        value={valuesVisible ? money(openWeighted) : String(openCount)}
        icon={Scale}
        tone="brand"
        sub={`${openCount} open deals`}
      />
      <StatTile
        label="Closed won"
        value={valuesVisible ? money(t.CLOSED_WON.value) : String(t.CLOSED_WON.count)}
        icon={Trophy}
        tone="good"
        sub={`${t.CLOSED_WON.count} deals`}
      />
      <StatTile
        label="Quota attainment"
        value={bucket.attainment == null ? "—" : `${bucket.attainment}%`}
        icon={Gauge}
        tone={bucket.attainment == null ? "brand" : bucket.attainment >= 100 ? "good" : bucket.attainment >= 70 ? "warning" : "critical"}
        sub={bucket.quota == null ? "No quota set" : `of ${money(bucket.quota)}`}
      />
    </div>
  );
}

function CategoryColumns({ bucket, valuesVisible }: { bucket: ForecastBucket; valuesVisible: boolean }) {
  const colors = useCategoryColors();
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-5 gap-3">
      {SHOWN.map((k) => {
        const c = bucket.totals[k];
        const muted = k === "NOT_FORECASTED";
        return (
          <div key={k} className="glass-card p-4 min-w-0" style={{ opacity: muted ? 0.8 : 1 }}>
            <div className="flex items-center gap-2">
              <span className="h-2.5 w-2.5 rounded-sm shrink-0" style={{ backgroundColor: colors[k] }} />
              <span className="text-xs font-semibold uppercase tracking-wide truncate" style={{ color: "var(--ink-muted)" }}>
                {label(k)}
              </span>
            </div>
            <p className="mt-2 text-xl font-bold tabular-nums truncate" style={{ color: "var(--ink-primary)" }}>
              {valuesVisible ? money(c.weighted) : c.count}
            </p>
            <p className="text-xs tabular-nums truncate" style={{ color: "var(--ink-muted)" }}>
              {valuesVisible ? `${money(c.value)} total · ${c.count} ${c.count === 1 ? "deal" : "deals"}` : c.count === 1 ? "deal" : "deals"}
            </p>
            {valuesVisible && !muted && k !== "CLOSED_WON" && bucket.cumulative[k] != null && (
              <p className="text-[11px] mt-1 truncate" style={{ color: "var(--ink-muted)" }} title={`${label(k)} and every higher category`}>
                {money(bucket.cumulative[k])} incl. higher
              </p>
            )}
          </div>
        );
      })}
    </div>
  );
}

function TrendChart({ buckets, valuesVisible }: { buckets: ForecastBucket[]; valuesVisible: boolean }) {
  const palette = useChartPalette();
  const colors = useCategoryColors();
  const keys: ForecastCategoryKey[] = ["CLOSED_WON", "COMMIT", "BEST_CASE", "PIPELINE"];
  const data = buckets.map((b) => {
    const row: Record<string, string | number> = { label: b.label };
    for (const k of keys) row[k] = valuesVisible ? b.totals[k].weighted ?? 0 : b.totals[k].count;
    return row;
  });
  const empty = data.every((r) => keys.every((k) => !Number(r[k])));
  if (empty) {
    return (
      <p className="text-sm py-14 text-center" style={{ color: "var(--ink-muted)" }}>
        No deals with an expected close date in these periods.
      </p>
    );
  }
  const fmt = (v: number) => (valuesVisible ? formatCompactCurrency(v) : String(v));
  return (
    <ResponsiveContainer width="100%" height={260}>
      <BarChart data={data} margin={{ left: 0, right: 8, top: 4, bottom: 0 }} barCategoryGap="28%">
        <CartesianGrid vertical={false} stroke={palette.grid} />
        <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fill: palette.ink, fontSize: 12 }} />
        <YAxis tickLine={false} axisLine={false} width={64} tick={{ fill: palette.axis, fontSize: 11 }} tickFormatter={fmt} />
        <Tooltip
          cursor={{ fill: "rgba(28,163,230,0.06)" }}
          content={({ active, payload, label: l }) => (
            <ChartTooltip
              active={active}
              label={l as string}
              payload={payload?.map((p) => ({
                name: label(p.dataKey as ForecastCategoryKey),
                value: p.value as number,
                color: colors[p.dataKey as ForecastCategoryKey],
              }))}
              formatter={fmt}
            />
          )}
        />
        <Legend
          iconType="square"
          iconSize={10}
          formatter={(v: string) => (
            <span className="text-xs" style={{ color: palette.ink }}>
              {label(v as ForecastCategoryKey)}
            </span>
          )}
        />
        {keys.map((k, i) => (
          <Bar key={k} dataKey={k} stackId="f" fill={colors[k]} radius={i === keys.length - 1 ? [6, 6, 0, 0] : 0} maxBarSize={56} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  );
}

function AttainmentBar({ figures }: { figures: Pick<ForecastFigures, "quota" | "closedWon" | "commitAndWon"> }) {
  const palette = useChartPalette();
  if (figures.quota == null || figures.quota <= 0) return null;
  const won = Math.min(100, ((figures.closedWon ?? 0) / figures.quota) * 100);
  const commit = Math.min(100 - won, (((figures.commitAndWon ?? 0) - (figures.closedWon ?? 0)) / figures.quota) * 100);
  return (
    <div
      className="h-2.5 rounded-full overflow-hidden flex"
      style={{ backgroundColor: "rgb(var(--panel-border) / 0.1)" }}
      role="img"
      aria-label={`Closed won ${Math.round(won)}% and commit ${Math.round(commit)}% of quota`}
    >
      <div style={{ width: `${won}%`, backgroundColor: palette.series[2] }} />
      <div style={{ width: `${Math.max(0, commit)}%`, backgroundColor: palette.series[1] }} />
    </div>
  );
}

function QuotaPanel({ bucket, valuesVisible }: { bucket: ForecastBucket; valuesVisible: boolean }) {
  const colors = useCategoryColors();
  return (
    <div className="flex flex-col h-full">
      <h3 className="text-sm font-semibold mb-1" style={{ color: "var(--ink-primary)" }}>
        Quota attainment
      </h3>
      <p className="text-xs mb-5" style={{ color: "var(--ink-muted)" }}>
        Commit + Closed won against the quota for {bucket.label}
      </p>
      {!valuesVisible ? (
        <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
          Hidden for your role.
        </p>
      ) : bucket.quota == null ? (
        <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
          No quota set for this period. Managers can set quotas per owner in the table below.
        </p>
      ) : (
        <>
          <div className="flex items-end justify-between gap-3 mb-2">
            <span className="text-3xl font-bold tabular-nums" style={{ color: "var(--ink-primary)" }}>
              {bucket.attainment ?? 0}%
            </span>
            <span className="text-sm tabular-nums" style={{ color: "var(--ink-muted)" }}>
              {money(bucket.commitAndWon)} / {money(bucket.quota)}
            </span>
          </div>
          <AttainmentBar figures={bucket} />
          <div className="flex flex-wrap gap-x-4 gap-y-1 mt-3 text-xs" style={{ color: "var(--ink-secondary)" }}>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-2 w-2 rounded-sm" style={{ backgroundColor: colors.CLOSED_WON }} /> Closed won {money(bucket.closedWon)}
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-2 w-2 rounded-sm" style={{ backgroundColor: colors.COMMIT }} /> Commit {money(bucket.totals.COMMIT.value)}
            </span>
          </div>
          <p className="text-xs mt-4" style={{ color: "var(--ink-muted)" }}>
            Gap to quota:{" "}
            <strong style={{ color: "var(--ink-primary)" }}>{money(Math.max(0, bucket.quota - (bucket.commitAndWon ?? 0)))}</strong> · Best case adds{" "}
            {money(bucket.totals.BEST_CASE.value)}
          </p>
        </>
      )}
    </div>
  );
}

function GroupTable({
  groups,
  valuesVisible,
  editableQuota,
  period,
  periodStartDate,
}: {
  groups: ForecastGroup[];
  valuesVisible: boolean;
  editableQuota: boolean;
  period: ForecastPeriod;
  periodStartDate: string;
}) {
  const [editing, setEditing] = useState<number | null>(null);
  const [amount, setAmount] = useState("");
  const qc = useQueryClient();
  const save = useMutation({
    mutationFn: async (userId: number) =>
      api.put("/forecast/quotas", { userId, period, periodStart: periodStartDate, amount: amount === "" ? null : Number(amount) }),
    onSuccess: () => {
      toast.success("Quota saved");
      setEditing(null);
      qc.invalidateQueries({ queryKey: ["forecast"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (groups.length === 0) {
    return (
      <p className="text-sm px-5 pb-6 pt-2" style={{ color: "var(--ink-muted)" }}>
        Nobody in your view has deals closing in this period yet.
      </p>
    );
  }
  const cell = (c: ForecastFigures["totals"][ForecastCategoryKey]) => (valuesVisible ? money(c.weighted) : String(c.count));
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm min-w-[720px]">
        <thead>
          <tr className="hairline border-y text-xs uppercase tracking-wide" style={{ color: "var(--ink-muted)" }}>
            <th className="text-left font-semibold px-5 py-2.5">Name</th>
            {(["PIPELINE", "BEST_CASE", "COMMIT", "CLOSED_WON"] as ForecastCategoryKey[]).map((k) => (
              <th key={k} className="text-right font-semibold px-3 py-2.5">
                {label(k)}
              </th>
            ))}
            <th className="text-right font-semibold px-3 py-2.5">Commit + won</th>
            <th className="text-left font-semibold px-5 py-2.5 w-[220px]">Quota</th>
          </tr>
        </thead>
        <tbody>
          {groups.map((g) => (
            <tr key={`${g.id ?? "none"}-${g.name}`} className="hairline border-b last:border-b-0">
              <td className="px-5 py-3">
                <div className="flex items-center gap-2.5 min-w-0">
                  <Avatar name={g.name} color={g.color ?? undefined} size={26} />
                  <span className="font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                    {g.name}
                  </span>
                </div>
              </td>
              {(["PIPELINE", "BEST_CASE", "COMMIT", "CLOSED_WON"] as ForecastCategoryKey[]).map((k) => (
                <td key={k} className="text-right tabular-nums px-3 py-3" style={{ color: "var(--ink-secondary)" }}>
                  {cell(g.totals[k])}
                </td>
              ))}
              <td className="text-right tabular-nums font-semibold px-3 py-3" style={{ color: "var(--ink-primary)" }}>
                {valuesVisible ? money(g.commitAndWon) : g.totals.COMMIT.count + g.totals.CLOSED_WON.count}
              </td>
              <td className="px-5 py-3">
                {editableQuota && g.id != null && editing === g.id ? (
                  <form
                    className="flex items-center gap-1.5"
                    onSubmit={(e) => {
                      e.preventDefault();
                      save.mutate(g.id!);
                    }}
                  >
                    <input
                      className="input py-1.5 w-28 tabular-nums"
                      type="number"
                      min={0}
                      autoFocus
                      aria-label={`Quota for ${g.name}`}
                      placeholder="No quota"
                      value={amount}
                      onChange={(e) => setAmount(e.target.value)}
                    />
                    <button className="btn-primary py-1.5" type="submit" disabled={save.isPending}>
                      Save
                    </button>
                    <button className="btn-ghost py-1.5" type="button" onClick={() => setEditing(null)}>
                      Cancel
                    </button>
                  </form>
                ) : (
                  <div className="flex items-center gap-2">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between text-xs tabular-nums mb-1" style={{ color: "var(--ink-muted)" }}>
                        <span>{g.quota == null ? "No quota" : money(g.quota)}</span>
                        {g.attainment != null && <span className="font-semibold">{g.attainment}%</span>}
                      </div>
                      <AttainmentBar figures={g} />
                    </div>
                    {editableQuota && g.id != null && valuesVisible && (
                      <button
                        className="btn-icon shrink-0"
                        aria-label={`Set quota for ${g.name}`}
                        title="Set quota"
                        onClick={() => {
                          setEditing(g.id);
                          setAmount(g.quota == null ? "" : String(g.quota));
                        }}
                      >
                        <Pencil size={14} />
                      </button>
                    )}
                  </div>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
