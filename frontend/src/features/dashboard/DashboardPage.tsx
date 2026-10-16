import { useQuery } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Target, TrendingUp, Trophy } from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { formatCompactCurrency, roleLabel } from "@/lib/format";
import type { DashboardStats, FunnelRow, LeadSourceRow, OppPipelineRow, TeamStatRow } from "@/lib/types";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { StatTile } from "@/components/ui/StatTile";
import { Skeleton } from "@/components/ui/Skeleton";
import { QueryError } from "@/components/ui/QueryError";
import { DonutChart } from "@/components/charts/DonutChart";
import { HBarChart } from "@/components/charts/HBarChart";

export default function DashboardPage() {
  const user = useAuthStore((s) => s.user);

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

  const scopeLabel =
    stats.data?.scope === "org" ? "Organization-wide" : stats.data?.scope === "team" ? "Your team" : "Your records";

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={`Welcome back, ${user?.name?.split(" ")[0] ?? ""}`}
        description={`${scopeLabel} · ${roleLabel(user?.role)}`}
        actions={
          <>
            <Link to="/leads" className="btn-secondary">
              <Target size={15} /> View leads
            </Link>
            <Link to="/opportunities" className="btn-primary">
              <TrendingUp size={15} /> View pipeline
            </Link>
          </>
        }
      />

      <div className="grid grid-cols-2 xl:grid-cols-4 gap-3 sm:gap-4">
        {stats.isError ? (
          <div className="glass-card col-span-2 xl:col-span-4">
            <QueryError error={stats.error} onRetry={() => stats.refetch()} title="Couldn't load your numbers" compact />
          </div>
        ) : stats.isLoading || !stats.data ? (
          Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-[96px] sm:h-[118px] rounded-2xl" />)
        ) : (
          <>
            <StatTile
              label="Active Leads"
              value={stats.data.activeLeads}
              icon={Target}
              tone="brand"
              sub={`${stats.data.totalLeads} total`}
            />
            <StatTile
              label="Open Pipeline"
              value={stats.data.totalOppValue == null ? "—" : formatCompactCurrency(stats.data.totalOppValue)}
              icon={TrendingUp}
              tone="brand"
            />
            <StatTile
              label="Won Value"
              value={formatCompactCurrency(stats.data.wonValue)}
              icon={Trophy}
              tone="good"
              sub={`${stats.data.wonOpps} deals won`}
            />
            <StatTile
              label="Overdue Tasks"
              value={stats.data.overdueTasks}
              icon={stats.data.overdueTasks > 0 ? AlertTriangle : CheckCircle2}
              tone={stats.data.overdueTasks > 0 ? "critical" : "good"}
              sub={`${stats.data.openTasks} open`}
            />
          </>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-5 gap-4">
        <div className="glass-card p-4 lg:col-span-3">
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
              Opportunity pipeline by stage
            </h3>
            <Link to="/opportunities" className="text-xs font-semibold" style={{ color: "var(--brand-600)" }}>
              View board →
            </Link>
          </div>
          {pipeline.isError ? (
            <QueryError error={pipeline.error} onRetry={() => pipeline.refetch()} compact />
          ) : pipeline.isLoading || !pipeline.data ? (
            <Skeleton className="h-48" />
          ) : pipeline.data.every((r) => r.count === 0) ? (
            <p className="text-sm py-12 text-center" style={{ color: "var(--ink-muted)" }}>
              No opportunities yet.
            </p>
          ) : (
            <HBarChart
              data={pipeline.data.map((r) => ({ label: r.stage, value: r.value ?? r.count, count: r.count }))}
              formatter={(v) => (pipeline.data![0]?.value == null ? String(v) : formatCompactCurrency(v))}
              labelFormatter={(v, row) =>
                pipeline.data![0]?.value == null ? `${v} deals` : `${formatCompactCurrency(v)} · ${row.count}`
              }
              emptyText="Every open deal is still at zero value."
            />
          )}
        </div>

        <div className="glass-card p-4 lg:col-span-2">
          <h3 className="text-sm font-semibold mb-4" style={{ color: "var(--ink-primary)" }}>
            Lead sources
          </h3>
          {sources.isError ? (
            <QueryError error={sources.error} onRetry={() => sources.refetch()} compact />
          ) : sources.isLoading || !sources.data ? (
            <Skeleton className="h-48" />
          ) : sources.data.length === 0 ? (
            <p className="text-sm py-12 text-center" style={{ color: "var(--ink-muted)" }}>
              No leads yet.
            </p>
          ) : (
            <DonutChart data={sources.data.map((r) => ({ name: r.source, value: r.count }))} />
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-5 gap-4">
        <div className="glass-card p-4 lg:col-span-2">
          <h3 className="text-sm font-semibold mb-4" style={{ color: "var(--ink-primary)" }}>
            Lead funnel
          </h3>
          {funnel.isError ? (
            <QueryError error={funnel.error} onRetry={() => funnel.refetch()} compact />
          ) : funnel.isLoading || !funnel.data ? (
            <Skeleton className="h-48" />
          ) : (
            <HBarChart data={funnel.data.map((r) => ({ label: r.stage, value: r.count }))} emptyText="No leads yet." />
          )}
        </div>

        <div className="glass-card p-4 lg:col-span-3">
          <h3 className="text-sm font-semibold mb-4" style={{ color: "var(--ink-primary)" }}>
            Team leaderboard
          </h3>
          {team.isError ? (
            <QueryError error={team.error} onRetry={() => team.refetch()} compact />
          ) : team.isLoading || !team.data ? (
            <Skeleton className="h-48" />
          ) : team.data.length === 0 ? (
            <p className="text-sm py-12 text-center" style={{ color: "var(--ink-muted)" }}>
              No team data visible to your role.
            </p>
          ) : (
            <div className="space-y-1">
              {[...team.data]
                .sort((a, b) => (b.pipelineValue ?? 0) + (b.wonValue ?? 0) - ((a.pipelineValue ?? 0) + (a.wonValue ?? 0)) || b.leadsOwned - a.leadsOwned)
                .map((rep, i) => (
                  <div key={rep.id} className="flex items-center gap-3 py-2.5 hairline border-b last:border-b-0">
                    <span className="text-xs font-bold w-4 text-center" style={{ color: "var(--ink-muted)" }}>
                      {i + 1}
                    </span>
                    <Avatar name={rep.name} color={rep.color} size={30} />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                        {rep.name}
                      </p>
                      <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                        {roleLabel(rep.role)} · {rep.leadsOwned} leads
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="text-sm font-semibold tabular-nums" style={{ color: "var(--good)" }}>
                        {formatCompactCurrency(rep.wonValue)}
                      </p>
                      <p className="text-xs tabular-nums" style={{ color: "var(--ink-muted)" }}>
                        {formatCompactCurrency(rep.pipelineValue)} open
                      </p>
                    </div>
                  </div>
                ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
