import { useQuery } from "@tanstack/react-query";
import { BarChart3, Target, TrendingUp, Trophy } from "lucide-react";
import { api } from "@/lib/api";
import { formatCompactCurrency, roleLabel } from "@/lib/format";
import type { DashboardStats, FunnelRow, LeadSourceRow, OppPipelineRow, TeamStatRow } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StatTile } from "@/components/ui/StatTile";
import { Skeleton } from "@/components/ui/Skeleton";
import { Column, DataTable } from "@/components/ui/DataTable";
import { DonutChart } from "@/components/charts/DonutChart";
import { HBarChart } from "@/components/charts/HBarChart";

export default function ReportsPage() {
  const stats = useQuery({
    queryKey: ["reports", "dashboard"],
    queryFn: async () => (await api.get<DashboardStats>("/reports/dashboard")).data,
  });
  const pipeline = useQuery({
    queryKey: ["reports", "pipeline"],
    queryFn: async () => (await api.get<OppPipelineRow[]>("/reports/pipeline")).data,
  });
  const funnel = useQuery({
    queryKey: ["reports", "funnel"],
    queryFn: async () => (await api.get<FunnelRow[]>("/reports/funnel")).data,
  });
  const sources = useQuery({
    queryKey: ["reports", "lead-sources"],
    queryFn: async () => (await api.get<LeadSourceRow[]>("/reports/lead-sources")).data,
  });
  const team = useQuery({
    queryKey: ["reports", "team"],
    queryFn: async () => (await api.get<TeamStatRow[]>("/reports/team")).data,
  });

  const winRate =
    funnel.data && funnel.data.length > 0
      ? Math.round(((funnel.data.find((f) => f.stage === "Won")?.count ?? 0) / Math.max(1, funnel.data[0].count)) * 100)
      : null;

  const teamColumns: Column<TeamStatRow>[] = [
    {
      key: "name",
      header: "Team member",
      sortValue: (r) => r.name,
      render: (r) => (
        <div className="flex items-center gap-2.5">
          <Avatar name={r.name} color={r.color} size={28} />
          <div>
            <p className="text-sm font-medium" style={{ color: "var(--ink-primary)" }}>
              {r.name}
            </p>
            <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
              {roleLabel(r.role)}
            </p>
          </div>
        </div>
      ),
    },
    { key: "leadsOwned", header: "Leads owned", align: "right", sortValue: (r) => r.leadsOwned, render: (r) => <span className="tabular-nums text-sm">{r.leadsOwned}</span> },
    {
      key: "pipelineValue",
      header: "Open pipeline",
      align: "right",
      sortValue: (r) => r.pipelineValue,
      render: (r) => <span className="tabular-nums text-sm font-semibold">{formatCompactCurrency(r.pipelineValue)}</span>,
    },
    {
      key: "wonValue",
      header: "Won value",
      align: "right",
      sortValue: (r) => r.wonValue,
      render: (r) => (
        <span className="tabular-nums text-sm font-semibold" style={{ color: "var(--good)" }}>
          {formatCompactCurrency(r.wonValue)}
        </span>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Reports" description="Pipeline health, lead quality and team performance." icon={<BarChart3 size={20} />} />

      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
        {stats.isLoading || !stats.data ? (
          Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-[118px] rounded-2xl" />)
        ) : (
          <>
            <StatTile label="Active leads" value={stats.data.activeLeads} icon={Target} tone="brand" sub={`${stats.data.totalLeads} total`} />
            <StatTile label="Open pipeline" value={formatCompactCurrency(stats.data.totalOppValue)} icon={TrendingUp} tone="brand" />
            <StatTile label="Won value" value={formatCompactCurrency(stats.data.wonValue)} icon={Trophy} tone="good" sub={`${stats.data.wonOpps} deals`} />
            <StatTile
              label="Lead → Won rate"
              value={winRate != null ? `${winRate}%` : "—"}
              icon={Trophy}
              tone={winRate != null && winRate >= 20 ? "good" : "warning"}
            />
          </>
        )}
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
        <div className="glass-card p-5">
          <h3 className="text-sm font-semibold mb-4" style={{ color: "var(--ink-primary)" }}>
            Pipeline value by stage
          </h3>
          {pipeline.isLoading || !pipeline.data ? (
            <Skeleton className="h-56" />
          ) : (
            <HBarChart
              data={pipeline.data.map((r) => ({ label: r.stage, value: r.value ?? r.count }))}
              formatter={(v) => (pipeline.data![0]?.value == null ? String(v) : formatCompactCurrency(v))}
              height={260}
            />
          )}
        </div>
        <div className="glass-card p-5">
          <h3 className="text-sm font-semibold mb-4" style={{ color: "var(--ink-primary)" }}>
            Lead conversion funnel
          </h3>
          {funnel.isLoading || !funnel.data ? (
            <Skeleton className="h-56" />
          ) : (
            <HBarChart data={funnel.data.map((r) => ({ label: r.stage, value: r.count }))} height={260} />
          )}
        </div>
      </div>

      <div className="glass-card p-5">
        <h3 className="text-sm font-semibold mb-4" style={{ color: "var(--ink-primary)" }}>
          Lead sources breakdown
        </h3>
        {sources.isLoading || !sources.data ? (
          <Skeleton className="h-48" />
        ) : sources.data.length === 0 ? (
          <p className="text-sm py-10 text-center" style={{ color: "var(--ink-muted)" }}>
            No leads yet.
          </p>
        ) : (
          <DonutChart data={sources.data.map((r) => ({ name: r.source, value: r.count }))} />
        )}
      </div>

      <div>
        <h3 className="text-sm font-semibold mb-3" style={{ color: "var(--ink-primary)" }}>
          Team performance
        </h3>
        <DataTable columns={teamColumns} rows={team.data ?? []} loading={team.isLoading} emptyTitle="No team data visible to your role" />
      </div>
    </div>
  );
}
