import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { useChartPalette } from "@/lib/chartColors";
import { ChartTooltip } from "./ChartTooltip";

/**
 * Categorical donut — identity per slice. Fixed hue order from the
 * validated palette; past 8 categories the rest fold into "Other" (grey)
 * rather than generating a new hue, per the dataviz skill's hard rule.
 */
export function DonutChart({
  data,
  nameKey = "name",
  valueKey = "value",
  formatter,
}: {
  data: { [k: string]: string | number | null | undefined }[];
  nameKey?: string;
  valueKey?: string;
  formatter?: (v: number) => string;
}) {
  const palette = useChartPalette();
  const num = (r: { [k: string]: string | number | null | undefined }) => Number(r[valueKey] ?? 0) || 0;
  const sorted = [...data].sort((a, b) => num(b) - num(a));
  const top = sorted.slice(0, 8);
  const rest = sorted.slice(8);
  const restSum = rest.reduce((s, r) => s + num(r), 0);
  const chartData = restSum > 0 ? [...top, { [nameKey]: "Other", [valueKey]: restSum }] : top;
  const colorFor = (i: number) => (i < palette.series.length ? palette.series[i] : "#8793a3");

  return (
    <div className="flex items-center gap-6 flex-wrap sm:flex-nowrap">
      <div className="shrink-0" style={{ width: 168, height: 168 }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={chartData}
              dataKey={valueKey}
              nameKey={nameKey}
              innerRadius={48}
              outerRadius={78}
              paddingAngle={2}
              stroke={palette.surface}
              strokeWidth={2}
            >
              {chartData.map((_, i) => (
                <Cell key={i} fill={colorFor(i)} />
              ))}
            </Pie>
            <Tooltip
              content={({ active, payload }) => (
                <ChartTooltip
                  active={active}
                  payload={payload?.map((p) => ({
                    name: String(p.name ?? ""),
                    value: Number(p.value ?? 0),
                    color: (p.payload as { fill?: string } | undefined)?.fill,
                  }))}
                  formatter={formatter}
                />
              )}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <ul className="flex-1 min-w-[10rem] space-y-1.5">
        {chartData.map((d, i) => (
          <li key={i} className="flex items-center justify-between gap-3 text-sm">
            <span className="flex items-center gap-2 truncate" style={{ color: "var(--ink-secondary)" }}>
              <span className="h-2.5 w-2.5 rounded-sm shrink-0" style={{ backgroundColor: colorFor(i) }} />
              <span className="truncate">{d[nameKey]}</span>
            </span>
            <span className="font-semibold tabular-nums shrink-0" style={{ color: "var(--ink-primary)" }}>
              {formatter ? formatter(num(d)) : d[valueKey]}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
