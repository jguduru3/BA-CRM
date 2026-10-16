import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Send, Repeat } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { isReadOnly, useAuthStore } from "@/lib/auth";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { EnrollmentControls } from "./EnrollmentDrawer";
import { CHANNEL_ICON, CHANNEL_COLOR } from "./SequenceBuilder";
import { type Enrollment, isLive, type Sequence, STATUS_LABEL, STATUS_TONE } from "./sequenceTypes";

/**
 * "Enroll in sequence" for a Lead or Contact drawer. Shows the record's current
 * enrollments (with pause / resume / unenroll) and enrolls it in an active
 * sequence. Hidden for read-only roles; the server enforces EDIT on the record.
 *
 * Mount: <EnrollInSequenceButton relatedType="Lead" relatedId={lead.id} />
 */
export function EnrollInSequenceButton({
  relatedType,
  relatedId,
}: {
  relatedType: "Lead" | "Contact";
  relatedId: number;
}) {
  const role = useAuthStore((s) => s.user?.role);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<number | null>(null);
  const qc = useQueryClient();

  const sequences = useQuery({
    queryKey: ["sequences", "list"],
    queryFn: async () => (await api.get<Sequence[]>("/sequences")).data,
    enabled: open,
  });
  const enrollments = useQuery({
    queryKey: ["sequence-enrollments", "record", relatedType, relatedId],
    queryFn: async () =>
      (await api.get<Enrollment[]>("/sequence-enrollments", { params: { relatedType, relatedId } })).data,
    enabled: open,
  });

  const enroll = useMutation({
    mutationFn: async (sequenceId: number) =>
      (await api.post<Enrollment>(`/sequences/${sequenceId}/enrollments`, { relatedType, relatedId })).data,
    onSuccess: (e) => {
      toast.success(`Enrolled in "${e.sequenceName ?? "sequence"}"`);
      setSelected(null);
      qc.invalidateQueries({ queryKey: ["sequence-enrollments"] });
      qc.invalidateQueries({ queryKey: ["sequences"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (isReadOnly(role)) return null;

  const live = new Set((enrollments.data ?? []).filter((e) => isLive(e.status)).map((e) => e.sequenceId));
  const available = (sequences.data ?? []).filter((s) => s.active && s.steps.length > 0);

  return (
    <>
      <button className="btn-secondary" onClick={() => setOpen(true)}>
        <Repeat size={14} /> Enroll in sequence
      </button>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Sequences"
        width="34rem"
        footer={
          <>
            <button className="btn-ghost" onClick={() => setOpen(false)}>
              Close
            </button>
            <button
              className="btn-primary"
              disabled={selected == null || enroll.isPending}
              onClick={() => selected != null && enroll.mutate(selected)}
            >
              <Send size={14} /> {enroll.isPending ? "Enrolling…" : "Enroll"}
            </button>
          </>
        }
      >
        <div className="space-y-5">
          <section>
            <h3 className="field-label">Current enrollments</h3>
            {enrollments.isError ? (
              <QueryError error={enrollments.error} onRetry={() => enrollments.refetch()} compact />
            ) : enrollments.isLoading ? (
              <Skeleton className="h-12 w-full" />
            ) : (enrollments.data ?? []).length === 0 ? (
              <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
                Not enrolled in any sequence.
              </p>
            ) : (
              <ul className="space-y-2">
                {(enrollments.data ?? []).map((e) => (
                  <li key={e.id} className="glass-card p-3 flex flex-wrap items-center gap-2">
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                        {e.sequenceName ?? "Sequence"}
                      </p>
                      <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
                        Step {Math.min(e.currentStep + 1, e.stepCount)} of {e.stepCount} · enrolled {formatRelative(e.createdAt)}
                        {e.stopReason ? ` · ${e.stopReason}` : ""}
                      </p>
                    </div>
                    <span className={STATUS_TONE[e.status]}>{STATUS_LABEL[e.status]}</span>
                    <EnrollmentControls enrollment={e} />
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section>
            <h3 className="field-label">Enroll in</h3>
            {sequences.isError ? (
              <QueryError error={sequences.error} onRetry={() => sequences.refetch()} compact />
            ) : sequences.isLoading ? (
              <Skeleton className="h-24 w-full" />
            ) : available.length === 0 ? (
              <p className="text-sm" style={{ color: "var(--ink-muted)" }}>
                No active sequences yet. A manager can build one on the Sequences page.
              </p>
            ) : (
              <ul className="space-y-2" role="radiogroup" aria-label="Sequence">
                {available.map((s) => {
                  const already = live.has(s.id);
                  const checked = selected === s.id;
                  return (
                    <li key={s.id}>
                      <label
                        className="glass-card p-3 flex items-center gap-3 cursor-pointer"
                        style={{
                          opacity: already ? 0.55 : 1,
                          outline: checked ? "2px solid var(--brand-500)" : undefined,
                        }}
                      >
                        <input
                          type="radio"
                          name="sequence"
                          disabled={already}
                          checked={checked}
                          onChange={() => setSelected(s.id)}
                        />
                        <div className="min-w-0 flex-1">
                          <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                            {s.name}
                          </p>
                          <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
                            {already ? "Already enrolled" : `${s.steps.length} step${s.steps.length === 1 ? "" : "s"}`}
                          </p>
                        </div>
                        <span className="flex items-center gap-1" aria-hidden>
                          {s.steps.slice(0, 6).map((st, i) => {
                            const Icon = CHANNEL_ICON[st.channel];
                            return <Icon key={i} size={13} style={{ color: CHANNEL_COLOR[st.channel] }} />;
                          })}
                        </span>
                      </label>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </div>
      </Modal>
    </>
  );
}

export default EnrollInSequenceButton;
