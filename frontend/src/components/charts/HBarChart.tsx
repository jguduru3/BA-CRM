import { Bar, BarChart, Cell, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useChartPalette } from "@/lib/chartColors";
import { ChartTooltip } from "./ChartTooltip";

type Row = Record<string, string | number | null | undefined>;

/**
 * Single-measure horizontal bar list (funnel stages, pipeline stages, team
 * leaderboard). One series -> one hue (brand blue), so no legend is needed;
 * identity is carried by the row label, magnitude by bar length — per the
 * dataviz skill's form heuristic for a ranked single measure. Each bar carries
 * its formatted value at the bar end; when every value is zero an empty state
 * is shown instead of a row of invisible bars.
 */
export function HBarChart({
  data,
  valueKey = "value",
  labelKey = "label",
  formatter,
  labelFormatter,
  height,
  colorAt,
  showValueLabels = true,
  emptyText = "Nothing to show yet.",
}: {
  data: Row[];
  valueKey?: string;
  labelKey?: string;
  formatter?: (v: number) => string;
  /** Text at the bar end (defaults to `formatter`); receives the row for extra context such as a percentage. */
  labelFormatter?: (v: number, row: Row) => string;
  height?: number;
  colorAt?: (row: Row, i: number) => string | undefined;
  showValueLabels?: boolean;
  emptyText?: string;
}) {
  const palette = useChartPalette();
  const h = height ?? Math.max(160, data.length * 44);
  const allZero = data.length === 0 || data.every((r) => !Number(r[valueKey]));

  if (allZero) {
    return (
      <div className="flex items-center justify-center text-sm text-center px-4" style={{ minHeight: Math.min(h, 200), color: "var(--ink-muted)" }}>
        {emptyText}
      </div>
    );
  }

  const fmt = (v: number) => (formatter ? formatter(v) : v.toLocaleString("en-IN"));

  return (
    <ResponsiveContainer width="100%" height={h}>
      <BarChart data={data} layout="vertical" margin={{ left: 4, right: showValueLabels ? 64 : 20, top: 4, bottom: 4 }} barCategoryGap={14}>
        <XAxis type="number" hide />
        <YAxis
          type="category"
          dataKey={labelKey}
          width={118}
          tickLine={false}
          axisLine={false}
          tick={{ fill: palette.ink, fontSize: 12 }}
        />
        <Tooltip
          cursor={{ fill: "rgba(28,163,230,0.06)" }}
          content={({ active, payload, label }) => (
            <ChartTooltip
              active={active}
              label={label as string}
              payload={payload?.map((p) => ({ name: "Value", value: p.value as number, color: palette.series[0] }))}
              formatter={formatter}
            />
          )}
        />
        <Bar dataKey={valueKey} radius={[0, 6, 6, 0]} maxBarSize={22} isAnimationActive={false}>
          {data.map((row, i) => (
            <Cell key={i} fill={colorAt?.(row, i) || palette.series[0]} />
          ))}
          {showValueLabels && (
            <LabelList
              dataKey={valueKey}
              position="right"
              offset={8}
              style={{ fill: palette.ink, fontSize: 12, fontWeight: 600, fontVariantNumeric: "tabular-nums" }}
              formatter={(v: unknown) => {
                const n = Number(v ?? 0);
                return fmt(n);
              }}
              content={
                labelFormatter
                  ? (props: { x?: number | string; y?: number | string; width?: number | string; height?: number | string; index?: number; value?: unknown }) => {
                      const x = Number(props.x ?? 0) + Number(props.width ?? 0) + 8;
                      const y = Number(props.y ?? 0) + Number(props.height ?? 0) / 2;
                      const row = data[props.index ?? 0];
                      return (
                        <text x={x} y={y} dominantBaseline="central" fill={palette.ink} fontSize={12} fontWeight={600}>
                          {labelFormatter(Number(props.value ?? 0), row)}
                        </text>
                      );
                    }
                  : undefined
              }
            />
          )}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
