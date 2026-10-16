import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pause, Play, Square } from "lucide-react";
import { api } from "@/lib/api";
import { useAuthStore, isReadOnly } from "@/lib/auth";
import { formatDateTime, formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { Drawer } from "@/components/ui/Drawer";
import { QueryError } from "@/components/ui/QueryError";
import { SkeletonRows } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { CHANNEL_ICON, CHANNEL_COLOR } from "./SequenceBuilder";
import {
  CHANNEL_LABEL,
  type Enrollment,
  type EnrollmentDetail,
  EVENT_LABEL,
  isLive,
  STATUS_LABEL,
  STATUS_TONE,
} from "./sequenceTypes";

const EXEC_TONE: Record<string, string> = {
  SENT: "badge badge-good",
  SKIPPED: "badge badge-neutral",
  FAILED: "badge badge-critical",
  PENDING: "badge badge-warning",
};

/** Pause / resume / unenroll an enrollment. Shared by the drawer and the record button. */
export function useEnrollmentActions(onDone?: (e: Enrollment) => void) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, op }: { id: number; op: "pause" | "resume" | "stop" }) =>
      (await api.post<Enrollment>(`/sequence-enrollments/${id}/${op}`)).data,
    onSuccess: (e, v) => {
      toast.success(v.op === "pause" ? "Enrollment paused" : v.op === "resume" ? "Enrollment resumed" : "Unenrolled");
      qc.invalidateQueries({ queryKey: ["sequences"] });
      qc.invalidateQueries({ queryKey: ["sequence-enrollments"] });
      onDone?.(e);
    },
    onError: (e: Error) => toast.error(e.message),
  });
}

export function EnrollmentControls({ enrollment }: { enrollment: Enrollment }) {
  const role = useAuthStore((s) => s.user?.role);
  const action = useEnrollmentActions();
  const { confirm, node } = useConfirm();
  if (isReadOnly(role) || !isLive(enrollment.status)) return null;
  return (
    <div className="flex items-center gap-1.5">
      {enrollment.status === "ACTIVE" ? (
        <button className="btn-secondary" disabled={action.isPending} onClick={() => action.mutate({ id: enrollment.id, op: "pause" })}>
          <Pause size={13} /> Pause
        </button>
      ) : (
        <button className="btn-secondary" disabled={action.isPending} onClick={() => action.mutate({ id: enrollment.id, op: "resume" })}>
          <Play size={13} /> Resume
        </button>
      )}
      <button
        className="btn-ghost"
        disabled={action.isPending}
        onClick={async () => {
          if (await confirm("Unenroll from this sequence?", "No further steps will be sent. You can enroll again later."))
            action.mutate({ id: enrollment.id, op: "stop" });
        }}
      >
        <Square size={13} /> Unenroll
      </button>
      {node}
    </div>
  );
}

/** One enrollment: progress, step results and the full history. */
export function EnrollmentDrawer({ enrollmentId, onClose }: { enrollmentId: number | null; onClose: () => void }) {
  const q = useQuery({
    queryKey: ["sequence-enrollments", "detail", enrollmentId],
    queryFn: async () => (await api.get<EnrollmentDetail>(`/sequence-enrollments/${enrollmentId}`)).data,
    enabled: enrollmentId != null,
  });
  const e = q.data;

  return (
    <Drawer
      open={enrollmentId != null}
      onClose={onClose}
      title={e?.recordName ?? "Enrollment"}
      subtitle={e ? `${e.relatedType} · ${e.sequenceName ?? "Sequence"}` : undefined}
      width="32rem"
    >
      {q.isError ? (
        <QueryError error={q.error} onRetry={() => q.refetch()} title="Couldn't load this enrollment" compact />
      ) : !e ? (
        <SkeletonRows rows={5} cols={2} />
      ) : (
        <div className="space-y-6">
          <div className="flex flex-wrap items-center gap-2">
            <span className={STATUS_TONE[e.status]}>{STATUS_LABEL[e.status]}</span>
            <span className="text-xs tabular-nums" style={{ color: "var(--ink-muted)" }}>
              Step {Math.min(e.currentStep + 1, e.stepCount)} of {e.stepCount}
            </span>
            {e.status === "ACTIVE" && e.nextRunAt && (
              <span className="text-xs" style={{ color: "var(--ink-muted)" }}>
                · next {formatRelative(e.nextRunAt)}
              </span>
            )}
            <div className="ml-auto">
              <EnrollmentControls enrollment={e} />
            </div>
          </div>
          {e.stopReason && (
            <p className="text-sm" style={{ color: "var(--ink-secondary)" }}>
              {e.stopReason}
            </p>
          )}
          {e.skippedChannels.length > 0 && (
            <p className="text-xs" style={{ color: "var(--warning)" }}>
              Skipping {e.skippedChannels.map((c) => CHANNEL_LABEL[c]).join(" and ")} after an unrecoverable failure.
            </p>
          )}

          <section>
            <h3 className="field-label">Steps</h3>
            {e.executions.length === 0 ? (
              <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
                No step has run yet.
              </p>
            ) : (
              <ul className="space-y-2">
                {e.executions.map((x) => {
                  const Icon = CHANNEL_ICON[x.channel];
                  return (
                    <li key={x.stepIndex} className="flex items-start gap-3 glass-card p-3">
                      <Icon size={15} className="mt-0.5 shrink-0" style={{ color: CHANNEL_COLOR[x.channel] }} />
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-medium" style={{ color: "var(--ink-primary)" }}>
                            Step {x.stepIndex + 1} · {CHANNEL_LABEL[x.channel]}
                          </span>
                          <span className={EXEC_TONE[x.status] ?? "badge badge-neutral"}>{x.status.toLowerCase()}</span>
                        </div>
                        {x.detail && (
                          <p className="text-xs mt-0.5 break-words" style={{ color: "var(--ink-muted)" }}>
                            {x.detail}
                          </p>
                        )}
                      </div>
                      <span className="text-[11px] whitespace-nowrap" style={{ color: "var(--ink-muted)" }}>
                        {formatDateTime(x.completedAt ?? x.createdAt)}
                      </span>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>

          <section>
            <h3 className="field-label">History</h3>
            <ol className="relative border-l pl-4 space-y-3" style={{ borderColor: "rgb(var(--panel-border) / 0.15)" }}>
              {e.events.map((ev) => (
                <li key={ev.id} className="relative">
                  <span
                    className="absolute -left-[21px] top-1.5 h-2 w-2 rounded-full"
                    style={{ backgroundImage: "var(--brand-gradient)" }}
                    aria-hidden
                  />
                  <p className="text-sm" style={{ color: "var(--ink-primary)" }}>
                    {EVENT_LABEL[ev.type] ?? ev.type}
                    {ev.stepIndex != null && (
                      <span style={{ color: "var(--ink-muted)" }}> · step {ev.stepIndex + 1}</span>
                    )}
                  </p>
                  {ev.detail && (
                    <p className="text-xs break-words" style={{ color: "var(--ink-muted)" }}>
                      {ev.detail}
                    </p>
                  )}
                  <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
                    {formatDateTime(ev.createdAt)}
                  </p>
                </li>
              ))}
            </ol>
          </section>
        </div>
      )}
    </Drawer>
  );
}
