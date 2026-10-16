import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api } from "@/lib/api";
import { useChartPalette } from "@/lib/chartColors";
import { formatCompactCurrency } from "@/lib/format";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { ChartTooltip } from "@/components/charts/ChartTooltip";
import { HBarChart } from "@/components/charts/HBarChart";

export interface DealFunnelStage {
  stage: string;
  reached: number;
  conversionFromPrevious: number | null;
  conversionFromStart: number | null;
}
export interface DealFunnel {
  stages: DealFunnelStage[];
  won: number;
  lost: number;
  open: number;
  includeOpen: boolean;
  winRate: number | null;
  overallConversion: number | null;
}
export interface WinLossOwner {
  ownerId: number | null;
  name: string;
  color: string | null;
  won: number;
  lost: number;
  wonValue: number | null;
  lostValue: number | null;
  winRate: number | null;
}
export interface WinLossReason {
  outcome: "Won" | "Lost";
  reason: string;
  count: number;
  value: number | null;
}
export interface WinLoss {
  byOwner: WinLossOwner[];
  byReason: WinLossReason[];
  totals: { won: number; lost: number; wonValue: number | null; lostValue: number | null; winRate: number | null };
  valuesVisible: boolean;
}
export interface Velocity {
  stages: { stage: string; avgDays: number | null; samples: number }[];
  avgDaysToCloseWon: number | null;
  wonSamples: number;
}

const pct = (v: number | null | undefined) => (v == null ? "—" : `${v}%`);

function Card({ title, sub, children, className = "" }: { title: string; sub?: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={`glass-card p-5 min-w-0 ${className}`}>
      <h3 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
        {title}
      </h3>
      {sub && (
        <p className="text-xs mt-0.5" style={{ color: "var(--ink-muted)" }}>
          {sub}
        </p>
      )}
      <div className="mt-4">{children}</div>
    </div>
  );
}

/** Deal funnel (Pipedrive rule), win/loss by owner and reason, deal velocity. */
export function PipelineAnalytics() {
  const palette = useChartPalette();
  const [includeOpen, setIncludeOpen] = useState(false);
  const funnel = useQuery({
    queryKey: ["reports", "deal-funnel", includeOpen],
    queryFn: async () => (await api.get<DealFunnel>("/reports/deal-funnel", { params: { includeOpen } })).data,
  });
  const winLoss = useQuery({
    queryKey: ["reports", "win-loss"],
    queryFn: async () => (await api.get<WinLoss>("/reports/win-loss")).data,
  });
  const velocity = useQuery({
    queryKey: ["reports", "velocity"],
    queryFn: async () => (await api.get<Velocity>("/reports/velocity")).data,
  });

  return (
    <>
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
        <Card
          title="Deal stage conversion"
          sub="Won deals count through every stage; lost deals up to the stage they were lost in."
        >
          <label className="inline-flex items-center gap-2 text-xs mb-3 cursor-pointer" style={{ color: "var(--ink-secondary)" }}>
            <input type="checkbox" checked={includeOpen} onChange={(e) => setIncludeOpen(e.target.checked)} />
            Include open deals
          </label>
          {funnel.isError ? (
            <QueryError error={funnel.error} onRetry={() => funnel.refetch()} compact />
          ) : funnel.isLoading || !funnel.data ? (
            <Skeleton className="h-56" />
          ) : (
            <>
              <HBarChart
                data={funnel.data.stages.map((s) => ({ label: s.stage, value: s.reached, conv: s.conversionFromPrevious }))}
                labelFormatter={(v, row) => (row.conv == null || row.label === funnel.data!.stages[0].stage ? `${v}` : `${v} · ${row.conv}%`)}
                emptyText={includeOpen ? "No deals yet." : "No closed deals yet — tick “Include open deals” to see the open pipeline."}
                height={240}
              />
              <div className="grid grid-cols-3 gap-3 mt-4 pt-4 hairline border-t text-center">
                <Figure label="Win rate" value={pct(funnel.data.winRate)} sub={`${funnel.data.won} won · ${funnel.data.lost} lost`} />
                <Figure label="Overall conversion" value={pct(funnel.data.overallConversion)} sub="first stage → won" />
                <Figure label="Open in view" value={funnel.data.includeOpen ? String(funnel.data.open) : "—"} sub="open deals counted" />
              </div>
            </>
          )}
        </Card>

        <Card title="Win / loss by owner" sub="Closed deals per owner">
          {winLoss.isError ? (
            <QueryError error={winLoss.error} onRetry={() => winLoss.refetch()} compact />
          ) : winLoss.isLoading || !winLoss.data ? (
            <Skeleton className="h-56" />
          ) : winLoss.data.byOwner.length === 0 ? (
            <p className="text-sm py-12 text-center" style={{ color: "var(--ink-muted)" }}>
              No won or lost deals yet.
            </p>
          ) : (
            <ResponsiveContainer width="100%" height={Math.max(180, winLoss.data.byOwner.length * 40 + 40)}>
              <BarChart data={winLoss.data.byOwner} layout="vertical" margin={{ left: 4, right: 16, top: 0, bottom: 0 }} barCategoryGap={12}>
                <CartesianGrid horizontal={false} stroke={palette.grid} />
                <XAxis type="number" allowDecimals={false} tickLine={false} axisLine={false} tick={{ fill: palette.axis, fontSize: 11 }} />
                <YAxis type="category" dataKey="name" width={118} tickLine={false} axisLine={false} tick={{ fill: palette.ink, fontSize: 12 }} />
                <Tooltip
                  cursor={{ fill: "rgba(28,163,230,0.06)" }}
                  content={({ active, payload, label }) => (
                    <ChartTooltip
                      active={active}
                      label={label as string}
                      payload={payload?.map((p) => ({ name: String(p.name), value: p.value as number, color: p.color }))}
                    />
                  )}
                />
                <Legend iconType="square" iconSize={10} formatter={(v: string) => <span className="text-xs" style={{ color: palette.ink }}>{v}</span>} />
                <Bar dataKey="won" name="Won" stackId="wl" fill={palette.good} maxBarSize={20} isAnimationActive={false} />
                <Bar dataKey="lost" name="Lost" stackId="wl" fill={palette.critical} radius={[0, 6, 6, 0]} maxBarSize={20} isAnimationActive={false} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
        <Card title="Why we win and lose" sub="Closed deals by close reason">
          {winLoss.isError ? (
            <QueryError error={winLoss.error} onRetry={() => winLoss.refetch()} compact />
          ) : winLoss.isLoading || !winLoss.data ? (
            <Skeleton className="h-48" />
          ) : winLoss.data.byReason.length === 0 ? (
            <p className="text-sm py-12 text-center" style={{ color: "var(--ink-muted)" }}>
              No close reasons recorded yet.
            </p>
          ) : (
            <HBarChart
              data={winLoss.data.byReason.slice(0, 10).map((r) => ({ label: `${r.outcome === "Won" ? "✓" : "✕"} ${r.reason}`, value: r.count, outcome: r.outcome, amount: r.value }))}
              colorAt={(row) => (row.outcome === "Won" ? palette.good : palette.critical)}
              labelFormatter={(v, row) => (row.amount == null ? `${v}` : `${v} · ${formatCompactCurrency(Number(row.amount))}`)}
            />
          )}
        </Card>

        <Card title="Deal velocity" sub="Average days a deal spends in each stage">
          {velocity.isError ? (
            <QueryError error={velocity.error} onRetry={() => velocity.refetch()} compact />
          ) : velocity.isLoading || !velocity.data ? (
            <Skeleton className="h-48" />
          ) : (
            <>
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-xs uppercase tracking-wide hairline border-b" style={{ color: "var(--ink-muted)" }}>
                    <th className="text-left font-semibold py-2">Stage</th>
                    <th className="text-right font-semibold py-2">Avg days</th>
                    <th className="text-right font-semibold py-2">Deals measured</th>
                  </tr>
                </thead>
                <tbody>
                  {velocity.data.stages.map((s) => {
                    const max = Math.max(1, ...velocity.data!.stages.map((x) => x.avgDays ?? 0));
                    return (
                      <tr key={s.stage} className="hairline border-b last:border-b-0">
                        <td className="py-2.5" style={{ color: "var(--ink-primary)" }}>
                          {s.stage}
                        </td>
                        <td className="py-2.5 text-right">
                          <span className="inline-flex items-center gap-2 justify-end">
                            {s.avgDays != null && (
                              <span className="hidden sm:inline-block h-1.5 rounded-full" style={{ width: `${(s.avgDays / max) * 72}px`, backgroundColor: palette.series[0] }} />
                            )}
                            <span className="tabular-nums font-semibold" style={{ color: "var(--ink-primary)" }}>
                              {s.avgDays ?? "—"}
                            </span>
                          </span>
                        </td>
                        <td className="py-2.5 text-right tabular-nums" style={{ color: "var(--ink-muted)" }}>
                          {s.samples}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              <div className="mt-4 pt-4 hairline border-t flex items-center justify-between gap-3">
                <span className="text-sm" style={{ color: "var(--ink-secondary)" }}>
                  Average days from creation to won
                </span>
                <span className="text-xl font-bold tabular-nums" style={{ color: "var(--ink-primary)" }}>
                  {velocity.data.avgDaysToCloseWon ?? "—"}
                  <span className="text-xs font-medium ml-1" style={{ color: "var(--ink-muted)" }}>
                    {velocity.data.wonSamples} won
                  </span>
                </span>
              </div>
            </>
          )}
        </Card>
      </div>
    </>
  );
}

function Figure({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <div className="min-w-0">
      <p className="text-[11px] font-semibold uppercase tracking-wide truncate" style={{ color: "var(--ink-muted)" }}>
        {label}
      </p>
      <p className="text-lg font-bold tabular-nums" style={{ color: "var(--ink-primary)" }}>
        {value}
      </p>
      <p className="text-[11px] truncate" style={{ color: "var(--ink-muted)" }}>
        {sub}
      </p>
    </div>
  );
}
