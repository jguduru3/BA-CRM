import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowRightCircle, Gauge, Trash2, UserCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { formatDateTime } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Lead } from "@/lib/types";
import { Drawer } from "@/components/ui/Drawer";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { Modal } from "@/components/ui/Modal";
import { AttachmentList } from "@/components/ui/AttachmentList";
import { LeadForm } from "./LeadForm";
import type { LeadDraft } from "./LeadForm";
import { AiAssistPanel } from "./AiAssistPanel";

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
  const [scoreReason, setScoreReason] = useState("");
  const qc = useQueryClient();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const { confirm, node: confirmNode } = useConfirm();

  useEffect(() => {
    setDraft(lead ?? { stage: "New", source: "Direct", priority: "Medium", value: 0 });
  }, [lead, open]);

  const invalidate = () => qc.invalidateQueries({ queryKey: ["leads"] });

  const save = useMutation({
    mutationFn: async () => {
      if (lead) {
        return (await api.put<Lead>(`/leads/${lead.id}`, draft)).data;
      }
      return (await api.post<Lead>("/leads", draft)).data;
    },
    onSuccess: () => {
      toast.success(lead ? "Lead updated" : "Lead created");
      invalidate();
      onSaved?.();
      onClose();
    },
    onError: (e: Error) => toast.error(e.message),
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
      toast.success("Converted to opportunity");
      invalidate();
      qc.invalidateQueries({ queryKey: ["opportunities"] });
      onClose();
      navigate(`/opportunities?highlight=${res.opportunity.id}`);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const scoreOverride = useMutation({
    mutationFn: async () =>
      (await api.post(`/leads/${lead!.id}/score-override`, { score: scoreValue, reason: scoreReason })).data,
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

  const canOverrideScore = user?.role === "SUPER_ADMIN" || user?.role === "VP_SALES" || user?.role === "MANAGER";
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
              {!lead.ownerId && (
                <button className="btn-icon" title="Claim this lead" onClick={() => claim.mutate()}>
                  <UserCheck size={16} />
                </button>
              )}
              {!lead.convertedOppId && (
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
              <button
                className="btn-icon"
                title="Move to trash"
                style={{ color: "var(--critical)" }}
                onClick={async () => {
                  const ok = await confirm("Delete this lead?", `"${lead.name}" will be moved to trash and can be restored later.`);
                  if (ok) remove.mutate("Deleted from lead detail panel");
                }}
              >
                <Trash2 size={16} />
              </button>
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
          <div className="mt-5 pt-4 hairline border-t">
            <AttachmentList relatedType="Lead" relatedId={lead.id} />
          </div>
        )}
        <div className="flex items-center justify-end gap-2 mt-6 pt-4 hairline border-t">
          <button className="btn-ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn-primary" disabled={!draft.name || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? "Saving…" : lead ? "Save changes" : "Create lead"}
          </button>
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
          <div>
            <label className="field-label">Reason (required)</label>
            <textarea className="textarea" value={scoreReason} onChange={(e) => setScoreReason(e.target.value)} />
          </div>
          <div className="flex justify-end gap-2">
            <button className="btn-ghost" onClick={() => setScoreOpen(false)}>
              Cancel
            </button>
            <button
              className="btn-primary"
              disabled={!scoreReason.trim() || scoreOverride.isPending}
              onClick={() => scoreOverride.mutate()}
            >
              Save override
            </button>
          </div>
        </div>
      </Modal>
      {confirmNode}
    </>
  );
}
