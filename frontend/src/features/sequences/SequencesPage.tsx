import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2, Repeat } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { isManagerUp, useAuthStore } from "@/lib/auth";
import { formatDateTime, formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Column, DataTable } from "@/components/ui/DataTable";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { QueryError } from "@/components/ui/QueryError";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { CHANNEL_COLOR, CHANNEL_ICON, SequenceBuilder } from "./SequenceBuilder";
import { EnrollmentDrawer } from "./EnrollmentDrawer";
import {
  CHANNEL_LABEL,
  cumulativeHours,
  dayLabel,
  describeDelay,
  type Enrollment,
  type EnrollmentStatus,
  type Sequence,
  STATUS_LABEL,
  STATUS_TONE,
} from "./sequenceTypes";

type StatusFilter = "all" | EnrollmentStatus;
const FILTERS: StatusFilter[] = ["all", "ACTIVE", "PAUSED", "RESPONDED", "COMPLETED", "STOPPED"];

function StepTimeline({ sequence }: { sequence: Sequence }) {
  const offsets = cumulativeHours(sequence.steps);
  if (sequence.steps.length === 0) {
    return (
      <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
        This sequence has no steps yet.
      </p>
    );
  }
  return (
    <ol className="space-y-2">
      {sequence.steps.map((s, i) => {
        const Icon = CHANNEL_ICON[s.channel];
        const title = s.channel === "WHATSAPP" ? `Template ${s.waTemplateName}` : s.subject;
        return (
          <li key={s.id ?? i} className="flex items-start gap-3">
            <div className="flex flex-col items-center">
              <span
                className="flex items-center justify-center h-8 w-8 rounded-xl shrink-0"
                style={{ backgroundColor: "rgb(var(--panel-border) / 0.08)", color: CHANNEL_COLOR[s.channel] }}
              >
                <Icon size={15} />
              </span>
              {i < sequence.steps.length - 1 && (
                <span className="w-px flex-1 min-h-[1rem] mt-1" style={{ backgroundColor: "rgb(var(--panel-border) / 0.15)" }} />
              )}
            </div>
            <div className="min-w-0 flex-1 pb-2">
              <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                {CHANNEL_LABEL[s.channel]} · {title}
              </p>
              <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
                {dayLabel(offsets[i])} · {describeDelay(s.delayDays, s.delayHours)}
                {i === 0 ? " after enrolling" : ""}
              </p>
              {s.channel === "EMAIL" && s.body && (
                <p className="text-xs mt-1 line-clamp-2 whitespace-pre-line" style={{ color: "var(--ink-secondary)" }}>
                  {s.body}
                </p>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

export default function SequencesPage() {
  const role = useAuthStore((s) => s.user?.role);
  const canManage = isManagerUp(role);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [editing, setEditing] = useState<Sequence | "new" | null>(null);
  const [filter, setFilter] = useState<StatusFilter>("all");
  const [openEnrollment, setOpenEnrollment] = useState<number | null>(null);
  const { confirm, node: confirmNode } = useConfirm();
  const qc = useQueryClient();

  const list = useQuery({
    queryKey: ["sequences", "list"],
    queryFn: async () => (await api.get<Sequence[]>("/sequences")).data,
  });
  const selected = useMemo(
    () => (list.data ?? []).find((s) => s.id === selectedId) ?? null,
    [list.data, selectedId]
  );
  useEffect(() => {
    if (selectedId == null && list.data && list.data.length > 0) setSelectedId(list.data[0].id);
  }, [list.data, selectedId]);

  const enrollments = useQuery({
    queryKey: ["sequence-enrollments", "sequence", selectedId, filter],
    queryFn: async () =>
      (
        await api.get<Enrollment[]>(`/sequences/${selectedId}/enrollments`, {
          params: { status: filter === "all" ? undefined : filter, limit: 200 },
        })
      ).data,
    enabled: selectedId != null,
  });

  const toggle = useMutation({
    mutationFn: async (s: Sequence) => (await api.put<Sequence>(`/sequences/${s.id}`, { active: !s.active })).data,
    onSuccess: (s) => {
      toast.success(s.active ? "Sequence activated" : "Sequence paused — no steps will run");
      qc.invalidateQueries({ queryKey: ["sequences"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });
  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/sequences/${id}`),
    onSuccess: () => {
      toast.success("Sequence deleted");
      setSelectedId(null);
      qc.invalidateQueries({ queryKey: ["sequences"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const columns: Column<Enrollment>[] = [
    {
      key: "record",
      header: "Record",
      sortValue: (r) => r.recordName ?? "",
      render: (r) => (
        <div className="min-w-0">
          <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
            {r.recordName ?? `${r.relatedType} #${r.relatedId}`}
          </p>
          <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
            {r.relatedType}
          </p>
        </div>
      ),
    },
    {
      key: "status",
      header: "Status",
      sortValue: (r) => r.status,
      render: (r) => <span className={STATUS_TONE[r.status]}>{STATUS_LABEL[r.status]}</span>,
    },
    {
      key: "progress",
      header: "Step",
      sortValue: (r) => r.currentStep,
      render: (r) => (
        <span className="text-sm tabular-nums">
          {Math.min(r.currentStep + (r.status === "COMPLETED" ? 0 : 1), r.stepCount)}/{r.stepCount}
          {r.skippedChannels.length > 0 && (
            <span className="ml-1.5 text-[11px]" style={{ color: "var(--warning)" }}>
              skip {r.skippedChannels.map((c) => CHANNEL_LABEL[c]).join(", ")}
            </span>
          )}
        </span>
      ),
    },
    {
      key: "next",
      header: "Next step",
      hideOnMobile: true,
      sortValue: (r) => r.nextRunAt ?? "",
      render: (r) => (
        <span className="text-sm whitespace-nowrap" style={{ color: "var(--ink-secondary)" }}>
          {r.status === "ACTIVE" && r.nextRunAt ? formatRelative(r.nextRunAt) : "—"}
        </span>
      ),
    },
    {
      key: "enrolled",
      header: "Enrolled",
      hideOnMobile: true,
      sortValue: (r) => r.createdAt,
      render: (r) => <span className="text-sm whitespace-nowrap">{formatDateTime(r.createdAt)}</span>,
    },
  ];

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Sequences"
        description="Multi-channel follow-up — email, WhatsApp and tasks on a schedule, stopping as soon as someone replies."
        icon={<Repeat size={20} />}
        actions={
          canManage ? (
            <button className="btn-primary" onClick={() => setEditing("new")}>
              <Plus size={15} /> New sequence
            </button>
          ) : undefined
        }
      />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load sequences" />
        </div>
      ) : list.isLoading ? (
        <SkeletonCards count={3} />
      ) : (list.data ?? []).length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={Repeat}
            title="No sequences yet"
            description={
              canManage
                ? "Build a cadence of emails, WhatsApp templates and call tasks, then enroll leads and contacts from their record."
                : "Your manager hasn't built any sequences yet."
            }
            action={
              canManage ? (
                <button className="btn-primary" onClick={() => setEditing("new")}>
                  <Plus size={15} /> New sequence
                </button>
              ) : undefined
            }
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-[18rem_minmax(0,1fr)] gap-5 items-start">
          <ul className="space-y-2" aria-label="Sequences">
            {(list.data ?? []).map((s) => {
              const active = s.id === selectedId;
              return (
                <li key={s.id}>
                  <button
                    className="glass-card glass-card-hover w-full text-left p-4"
                    style={{ outline: active ? "2px solid var(--brand-500)" : undefined }}
                    onClick={() => {
                      setSelectedId(s.id);
                      setFilter("all");
                    }}
                  >
                    <div className="flex items-center gap-2">
                      <p className="text-sm font-semibold truncate flex-1" style={{ color: "var(--ink-primary)" }}>
                        {s.name}
                      </p>
                      <span className={s.active ? "badge badge-good" : "badge badge-neutral"}>{s.active ? "Active" : "Off"}</span>
                    </div>
                    <div className="mt-2 flex items-center gap-1.5">
                      {s.steps.slice(0, 8).map((st, i) => {
                        const Icon = CHANNEL_ICON[st.channel];
                        return <Icon key={i} size={13} style={{ color: CHANNEL_COLOR[st.channel] }} aria-hidden />;
                      })}
                      <span className="ml-auto text-[11px] tabular-nums" style={{ color: "var(--ink-muted)" }}>
                        {s.activeEnrollments} live
                      </span>
                    </div>
                  </button>
                </li>
              );
            })}
          </ul>

          {selected && (
            <div className="flex flex-col gap-5 min-w-0">
              <div className="glass-card p-5">
                <div className="flex flex-wrap items-start gap-3">
                  <div className="min-w-0 flex-1">
                    <h2 className="text-lg font-semibold" style={{ color: "var(--ink-primary)" }}>
                      {selected.name}
                    </h2>
                    {selected.description && (
                      <p className="text-sm mt-0.5" style={{ color: "var(--ink-secondary)" }}>
                        {selected.description}
                      </p>
                    )}
                    <p className="text-[11px] mt-1" style={{ color: "var(--ink-muted)" }}>
                      {selected.steps.length} steps ·{" "}
                      {Object.entries(selected.enrollmentCounts)
                        .map(([k, v]) => `${v} ${STATUS_LABEL[k as EnrollmentStatus].toLowerCase()}`)
                        .join(" · ") || "no enrollments yet"}
                    </p>
                  </div>
                  {canManage && (
                    <div className="flex items-center gap-1.5">
                      <button className="btn-secondary" disabled={toggle.isPending} onClick={() => toggle.mutate(selected)}>
                        {selected.active ? "Turn off" : "Activate"}
                      </button>
                      <button className="btn-secondary" onClick={() => setEditing(selected)}>
                        <Pencil size={13} /> Edit
                      </button>
                      <button
                        className="btn-icon"
                        aria-label="Delete sequence"
                        onClick={async () => {
                          if (
                            await confirm(
                              `Delete "${selected.name}"?`,
                              "Everyone still enrolled is unenrolled and nothing further is sent."
                            )
                          )
                            remove.mutate(selected.id);
                        }}
                      >
                        <Trash2 size={15} />
                      </button>
                    </div>
                  )}
                </div>
                <div className="mt-5">
                  <StepTimeline sequence={selected} />
                </div>
              </div>

              <div className="flex flex-col gap-3 min-w-0">
                <div className="overflow-x-auto thin-scroll -mx-1 px-1">
                  <Tabs
                    value={filter}
                    onChange={setFilter}
                    tabs={FILTERS.map((f) => ({
                      value: f,
                      label: f === "all" ? "All" : STATUS_LABEL[f],
                      count: f === "all" ? undefined : selected.enrollmentCounts[f] ?? 0,
                    }))}
                  />
                </div>
                {enrollments.isError ? (
                  <div className="glass-card">
                    <QueryError error={enrollments.error} onRetry={() => enrollments.refetch()} title="Couldn't load enrollments" />
                  </div>
                ) : (
                  <DataTable
                    columns={columns}
                    rows={enrollments.data ?? []}
                    loading={enrollments.isLoading}
                    onRowClick={(r) => setOpenEnrollment(r.id)}
                    emptyTitle={filter === "all" ? "Nobody enrolled yet" : "Nothing in this view"}
                    emptyDescription={
                      filter === "all" ? "Enroll a lead or contact from its record with “Enroll in sequence”." : undefined
                    }
                  />
                )}
              </div>
            </div>
          )}
        </div>
      )}

      <EnrollmentDrawer enrollmentId={openEnrollment} onClose={() => setOpenEnrollment(null)} />

      <Drawer
        open={editing != null}
        onClose={() => setEditing(null)}
        title={editing === "new" ? "New sequence" : "Edit sequence"}
        subtitle="Delays are measured from the previous step"
        width="40rem"
      >
        {editing != null && (
          <SequenceBuilder
            key={editing === "new" ? "new" : editing.id}
            sequence={editing === "new" ? null : editing}
            onCancel={() => setEditing(null)}
            onSaved={(s) => {
              setEditing(null);
              setSelectedId(s.id);
            }}
          />
        )}
      </Drawer>
      {confirmNode}
    </div>
  );
}
