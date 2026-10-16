import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowRightCircle, Gauge, Trash2, UserCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "@/lib/api";
import { isManagerUp, isReadOnly, useAuthStore } from "@/lib/auth";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Lead } from "@/lib/types";
import { Drawer } from "@/components/ui/Drawer";
import { Modal } from "@/components/ui/Modal";
import { useReason } from "@/components/ui/ReasonDialog";
import { saveErrorMessage } from "@/features/crm/ListParts";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { LeadForm } from "./LeadForm";
import type { LeadDraft } from "./LeadForm";
import { AiAssistPanel } from "./AiAssistPanel";
import { RecordPagePanel } from "@/features/recordpages/RecordPagePanel";
import { DuplicateDialog } from "@/features/duplicates/DuplicateDialog";
import { isDuplicateError, saveChecked } from "@/features/duplicates/duplicates";
import type { DuplicateError } from "@/features/duplicates/duplicates";

export function LeadDrawer({
  lead,
  open,
  onClose,
  onSaved,
}: {
  lead: Lead | null; // null = create new
  open: boolean;
  onClose: () => void;
  onSaved?: () => void;
}) {
  const [draft, setDraft] = useState<LeadDraft>({});
  const [scoreOpen, setScoreOpen] = useState(false);
  const [scoreValue, setScoreValue] = useState(0);
  const [duplicate, setDuplicate] = useState<DuplicateError | null>(null);
  const qc = useQueryClient();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const { askReason, node: reasonNode } = useReason();
  const readOnly = isReadOnly(user?.role);

  useEffect(() => {
    setDraft(lead ?? { stage: "New", source: "Direct", priority: "Medium", value: 0 });
  }, [lead, open]);

  const invalidate = () => qc.invalidateQueries({ queryKey: ["leads"] });

  const save = useMutation({
    mutationFn: async (allowDuplicate: boolean = false) => {
      if (lead) {
        // Send only what changed (+ the version we loaded): untouched fields can't
        // clobber someone else's edit, and a concurrent change comes back as 409.
        const changed: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(draft)) {
          if (v !== (lead as unknown as Record<string, unknown>)[k]) changed[k] = v;
        }
        const version = (lead as { version?: number }).version;
        if (version !== undefined) changed.version = version;
        return saveChecked<Lead>("patch", `/leads/${lead.id}`, changed, "lead", allowDuplicate);
      }
      return saveChecked<Lead>("post", "/leads", draft, "lead", allowDuplicate);
    },
    onSuccess: () => {
      setDuplicate(null);
      toast.success(lead ? "Lead updated" : "Lead created");
      invalidate();
      onSaved?.();
      onClose();
    },
    onError: (e: Error) => (isDuplicateError(e) ? setDuplicate(e) : toast.error(saveErrorMessage(e, "lead"))),
  });

  const claim = useMutation({
    mutationFn: async () => (await api.post<Lead>(`/leads/${lead!.id}/claim`)).data,
    onSuccess: () => {
      toast.success("Lead claimed");
      invalidate();
      onClose();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const convert = useMutation({
    mutationFn: async () => (await api.post(`/leads/${lead!.id}/convert`)).data,
    onSuccess: (res) => {
      toast.success(res.created === false ? "Already converted — opening the deal" : "Converted to opportunity");
      invalidate();
      qc.invalidateQueries({ queryKey: ["opportunities"] });
      onClose();
      navigate(`/opportunities?highlight=${res.opportunity.id}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const scoreOverride = useMutation({
    mutationFn: async (reason: string) =>
      (await api.post(`/leads/${lead!.id}/score-override`, { score: scoreValue, reason })).data,
    onSuccess: () => {
      toast.success("Score updated");
      invalidate();
      setScoreOpen(false);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (reason: string) => api.delete(`/leads/${lead!.id}`, { params: { reason } }),
    onSuccess: () => {
      toast.success("Lead moved to trash");
      invalidate();
      onClose();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const canOverrideScore = isManagerUp(user?.role);
  const canClaim = user?.role === "EXECUTIVE" || user?.role === "INSIDE_SALES" || user?.role === "FIELD_SALES";
  const disableSourceEdit = !!lead && user?.role !== "SUPER_ADMIN" && user?.role !== "VP_SALES" && user?.role !== "MANAGER";

  return (
    <>
      <Drawer
        open={open}
        onClose={onClose}
        title={lead ? lead.name : "New lead"}
        subtitle={
          lead ? (
            <span>
              Created {formatDateTime(lead.createdAt)}
              {lead.followUpRescheduleCount ? ` · rescheduled ${lead.followUpRescheduleCount}×` : ""}
            </span>
          ) : (
            "Capture a new prospect"
          )
        }
        width="38rem"
        headerExtra={
          lead && (
            <>
              {!lead.ownerId && canClaim && (
                <button className="btn-icon" title="Claim this lead" onClick={() => claim.mutate()}>
                  <UserCheck size={16} />
                </button>
              )}
              {!lead.convertedOppId && !readOnly && (
                <button className="btn-icon" title="Convert to opportunity" onClick={() => convert.mutate()}>
                  <ArrowRightCircle size={16} />
                </button>
              )}
              {canOverrideScore && (
                <button
                  className="btn-icon"
                  title="Override lead score"
                  onClick={() => {
                    setScoreValue(lead.leadScore ?? 0);
                    setScoreOpen(true);
                  }}
                >
                  <Gauge size={16} />
                </button>
              )}
              {canOverrideScore && (
                <button
                  className="btn-icon"
                  title="Move to trash"
                  style={{ color: "var(--critical)" }}
                  onClick={async () => {
                    const reason = await askReason({
                      title: "Move this lead to trash?",
                      description: `"${lead.name}" can be restored from Trash later.`,
                      confirmLabel: "Move to trash",
                      danger: true,
                    });
                    if (reason) remove.mutate(reason);
                  }}
                >
                  <Trash2 size={16} />
                </button>
              )}
            </>
          )
        }
      >
        {lead?.convertedOppId && (
          <div
            className="mb-4 rounded-lg px-3 py-2.5 text-sm flex items-center justify-between"
            style={{ backgroundColor: "rgb(12 163 12 / 0.1)", color: "var(--good)" }}
          >
            <span>Converted to an opportunity</span>
            <button
              className="font-semibold underline"
              onClick={() => navigate(`/opportunities?highlight=${lead.convertedOppId}`)}
            >
              View deal →
            </button>
          </div>
        )}
        {lead && !lead.ownerId && (
          <div
            className="mb-4 rounded-lg px-3 py-2.5 text-sm"
            style={{ backgroundColor: "rgb(237 161 0 / 0.12)", color: "var(--ink-secondary)" }}
          >
            <strong style={{ color: "var(--ink-primary)" }}>In the unassigned pool.</strong>{" "}
            {canClaim ? "Claim it to make it yours." : "Pick an owner below to assign it."}
          </div>
        )}
        {lead?.leadScoreOverrideReason && (
          <div
            className="mb-4 rounded-lg px-3 py-2.5 text-xs"
            style={{ backgroundColor: "rgb(var(--ring-brand) / 0.08)", color: "var(--ink-secondary)" }}
          >
            <strong style={{ color: "var(--ink-primary)" }}>Score overridden:</strong> {lead.leadScoreOverrideReason}
          </div>
        )}
        {lead && <AiAssistPanel lead={lead} />}
        <LeadForm value={draft} onChange={(p) => setDraft((d) => ({ ...d, ...p }))} disableSourceEdit={disableSourceEdit} />
        {lead && (
          <div className="mt-5 pt-4 hairline border-t space-y-5">
            <RecordPagePanel relatedType="Lead" relatedId={lead.id} readOnly={readOnly} />
            <AttachmentList relatedType="Lead" relatedId={lead.id} />
          </div>
        )}
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          {!readOnly && (
            <button className="btn-primary" disabled={!draft.name || save.isPending} onClick={() => save.mutate(false)}>
              {save.isPending ? "Saving…" : lead ? "Save changes" : "Create lead"}
            </button>
          )}
        </div>
      </Drawer>

      <Modal open={scoreOpen} onClose={() => setScoreOpen(false)} title="Override lead score" width="24rem">
        <div className="space-y-4">
          <div>
            <label className="field-label">Score (0–100)</label>
            <input
              type="number"
              min={0}
              max={100}
              className="input"
              value={scoreValue}
              onChange={(e) => setScoreValue(Number(e.target.value))}
            />
          </div>
          <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
            Next you'll be asked why — the reason is stored on the lead and in the audit log.
          </p>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setScoreOpen(false)}>
              Cancel
            </button>
            <button
              className="btn-primary"
              disabled={scoreOverride.isPending || scoreValue < 0 || scoreValue > 100}
              onClick={async () => {
                setScoreOpen(false);
                const reason = await askReason({
                  title: `Override score to ${scoreValue}?`,
                  description: "Explain why the system score is wrong for this lead.",
                  label: "Reason for override",
                  confirmLabel: "Save override",
                });
                if (reason) scoreOverride.mutate(reason);
              }}
            >
              Continue
            </button>
          </div>
        </div>
      </Modal>
      {reasonNode}
      <DuplicateDialog
        error={duplicate}
        saving={save.isPending}
        onClose={() => setDuplicate(null)}
        onSaveAnyway={() => save.mutate(true)}
      />
    </>
  );
}
