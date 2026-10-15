import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { useChartPalette } from "@/lib/chartColors";
import { ChartTooltip } from "./ChartTooltip";

/**
 * Single-measure horizontal bar list (funnel stages, pipeline stages, team
 * leaderboard). One series -> one hue (brand blue), so no legend is needed;
 * identity is carried by the row label, magnitude by bar length — per the
 * dataviz skill's form heuristic for a ranked single measure.
 */
export function HBarChart({
  data,
  valueKey = "value",
  labelKey = "label",
  formatter,
  height,
  colorAt,
}: {
  data: Record<string, string | number | null | undefined>[];
  valueKey?: string;
  labelKey?: string;
  formatter?: (v: number) => string;
  height?: number;
  colorAt?: (row: Record<string, string | number | null | undefined>, i: number) => string | undefined;
}) {
  const palette = useChartPalette();
  const h = height ?? Math.max(160, data.length * 44);

  return (
    <ResponsiveContainer width="100%" height={h}>
      <BarChart data={data} layout="vertical" margin={{ left: 4, right: 20, top: 4, bottom: 4 }} barCategoryGap={14}>
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
        <Bar dataKey={valueKey} radius={[0, 6, 6, 0]} maxBarSize={22}>
          {data.map((row, i) => (
            <Cell key={i} fill={colorAt?.(row, i) || palette.series[0]} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
