import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { LucideIcon } from "lucide-react";
import { Ban, CheckCircle2, ChevronLeft, ChevronRight, Clock, Pause, Pencil, Play, Plus, Rocket, Send, Trash2, XCircle } from "lucide-react";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { formatDateTime, formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { Drawer } from "@/components/ui/Drawer";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { useReason } from "@/components/ui/ReasonDialog";
import { AudienceBuilder } from "./AudienceBuilder";
import { CampaignForm, type CampaignDraft, validateTemplateParams } from "./CampaignForm";
import { CampaignProgressBar } from "./CampaignProgressBar";
import {
  type AddRecipientsResult,
  allowedActions,
  type CampaignAction,
  type CampaignDetail,
  type CampaignRecipient,
  canEditRecipients,
  normalizeStatus,
  STATUS_LABEL,
  STATUS_TONE,
} from "./campaignTypes";

const ACTION_META: Record<Exclude<CampaignAction, "edit" | "delete">, { label: string; icon: LucideIcon; tone: string }> = {
  submit: { label: "Submit for approval", icon: Send, tone: "btn-primary" },
  approve: { label: "Approve", icon: CheckCircle2, tone: "btn-primary" },
  reject: { label: "Reject", icon: XCircle, tone: "btn-danger" },
  launch: { label: "Launch", icon: Rocket, tone: "btn-primary" },
  pause: { label: "Pause", icon: Pause, tone: "btn-secondary" },
  resume: { label: "Resume", icon: Play, tone: "btn-primary" },
  cancel: { label: "Cancel campaign", icon: Ban, tone: "btn-ghost" },
};

const RECIPIENT_TONE: Record<string, string> = {
  pending: "badge-neutral",
  sending: "badge-brand",
  sent: "badge-brand",
  delivered: "badge-good",
  read: "badge-good",
  failed: "badge-critical",
  skipped_opted_out: "badge-warning",
  skipped: "badge-neutral",
};
const PAGE = 50;

export function CampaignDetailDrawer({ campaignId, onClose }: { campaignId: number | null; onClose: () => void }) {
  const qc = useQueryClient();
  const user = useAuthStore((s) => s.user);
  const { confirm, node: confirmNode } = useConfirm();
  const { askReason, node: reasonNode } = useReason();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<CampaignDraft>({});
  const [recipients, setRecipients] = useState("");
  const [lastAdd, setLastAdd] = useState<AddRecipientsResult | null>(null);
  const [statusFilter, setStatusFilter] = useState("");
  const [page, setPage] = useState(0);

  const detail = useQuery({
    queryKey: ["campaigns", "detail", campaignId],
    queryFn: async () => (await api.get<CampaignDetail>(`/campaigns/${campaignId}`)).data,
    enabled: campaignId != null,
    // Live progress while the background sender works.
    refetchInterval: (q) => (q.state.data && normalizeStatus(q.state.data.campaign.status) === "sending" ? 10_000 : false),
  });

  const recipientsPage = useQuery({
    queryKey: ["campaigns", "recipients", campaignId, statusFilter, page],
    queryFn: async () =>
      (
        await api.get<CampaignRecipient[]>(`/campaigns/${campaignId}/contacts`, {
          params: { page, size: PAGE, ...(statusFilter ? { status: statusFilter } : {}) },
        })
      ).data,
    enabled: campaignId != null,
    refetchInterval: detail.data && normalizeStatus(detail.data.campaign.status) === "sending" ? 10_000 : false,
  });

  useEffect(() => {
    setEditing(false);
    setRecipients("");
    setLastAdd(null);
    setStatusFilter("");
    setPage(0);
  }, [campaignId]);

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["campaigns"] });
  };
  const onError = (e: Error) => {
    toast.error(e instanceof ApiError && e.status === 409 ? `${e.message}` : e.message);
    if (e instanceof ApiError && e.status === 409) refresh();
  };

  const act = useMutation({
    mutationFn: async ({ key, reason }: { key: string; reason?: string }) =>
      api.post(`/campaigns/${campaignId}/${key}`, { version: detail.data?.campaign.version, ...(reason ? { reason } : {}) }),
    onSuccess: (_r, v) => {
      toast.success(
        v.key === "approve" ? "Campaign approved" : v.key === "reject" ? "Campaign sent back with your reason" : v.key === "launch" ? "Campaign launched" : "Campaign updated"
      );
      refresh();
    },
    onError,
  });

  const saveEdit = useMutation({
    mutationFn: async () => api.put(`/campaigns/${campaignId}`, { ...draft, version: detail.data?.campaign.version }),
    onSuccess: () => {
      toast.success("Campaign saved");
      setEditing(false);
      refresh();
    },
    onError,
  });

  const remove = useMutation({
    mutationFn: async () => api.delete(`/campaigns/${campaignId}`),
    onSuccess: () => {
      toast.success("Campaign deleted");
      refresh();
      onClose();
    },
    onError,
  });

  const addRecipients = useMutation({
    mutationFn: async () => {
      const entries = recipients
        .split(/[\n,;]+/)
        .map((l) => l.trim())
        .filter(Boolean)
        .map((phoneNumber) => ({ phoneNumber }));
      return (await api.post<AddRecipientsResult>(`/campaigns/${campaignId}/contacts`, entries)).data;
    },
    onSuccess: (r) => {
      setLastAdd(r);
      setRecipients("");
      toast.success(`${r.added} recipient${r.added === 1 ? "" : "s"} added`);
      refresh();
    },
    onError,
  });

  const removeRecipient = useMutation({
    mutationFn: async (ccId: number) => api.delete(`/campaigns/${campaignId}/contacts/${ccId}`),
    onSuccess: refresh,
    onError,
  });

  const c = detail.data?.campaign;
  const status = c ? normalizeStatus(c.status) : "draft";
  const actions = c && user ? allowedActions(c, { id: user.id, role: user.role }) : [];
  const editableRecipients = actions.includes("edit") && canEditRecipients(status);

  const runAction = async (key: CampaignAction) => {
    if (!c) return;
    if (key === "reject") {
      const reason = await askReason({
        title: "Reject this campaign?",
        description: "The creator sees your reason and can fix the campaign and resubmit it.",
        label: "Reason for rejection",
        confirmLabel: "Reject campaign",
        danger: true,
      });
      if (reason) act.mutate({ key, reason });
      return;
    }
    if (key === "launch") {
      const scheduled = c.scheduledAt && new Date(c.scheduledAt).getTime() > Date.now();
      const ok = await confirm(
        status === "draft" ? "Launch without approval?" : "Launch this campaign?",
        `${c.totalContacts} recipient${c.totalContacts === 1 ? "" : "s"} will be messaged${scheduled ? ` starting ${formatDateTime(c.scheduledAt)}` : " now"}. Opted-out numbers are skipped automatically.`,
        false
      );
      if (ok) act.mutate({ key });
      return;
    }
    if (key === "cancel") {
      const ok = await confirm("Cancel this campaign?", "Recipients not yet messaged will not be contacted. This can't be undone.");
      if (ok) act.mutate({ key });
      return;
    }
    if (key === "edit") {
      setDraft({
        name: c.name,
        type: c.type,
        waAccountId: c.waAccountId ?? null,
        templateName: c.templateName ?? "",
        templateLanguage: c.templateLanguage ?? "en_US",
        templateParams: c.templateParams ?? "",
        messageBody: c.messageBody ?? "",
        scheduledAt: c.scheduledAt ?? null,
      });
      setEditing(true);
      return;
    }
    if (key === "delete") {
      const ok = await confirm("Delete this campaign?", `"${c.name}" and its recipient list will be permanently deleted.`);
      if (ok) remove.mutate();
      return;
    }
    act.mutate({ key });
  };

  const progress = detail.data?.progress;
  const busy = act.isPending || saveEdit.isPending || remove.isPending;

  return (
    <Drawer
      open={campaignId != null}
      onClose={onClose}
      width="38rem"
      title={c?.name ?? "Campaign"}
      subtitle={
        c && (
          <span className="inline-flex items-center gap-2">
            <span className={STATUS_TONE[status]}>{STATUS_LABEL[status]}</span>
            <span className="capitalize">{c.type}</span>
            {status === "sending" && <span className="inline-flex items-center gap-1"><span className="h-1.5 w-1.5 rounded-full animate-pulse" style={{ backgroundColor: "var(--brand-500)" }} /> live</span>}
          </span>
        )
      }
    >
      {detail.isError ? (
        <QueryError error={detail.error} onRetry={() => detail.refetch()} title="Couldn't load this campaign" />
      ) : !c || !progress ? (
        <div className="space-y-3">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : editing ? (
        <div className="space-y-5">
          <CampaignForm value={draft} onChange={setDraft} isNew={false} />
          <div className="flex justify-end gap-2 pt-4 hairline border-t">
            <button className="btn-ghost" onClick={() => setEditing(false)}>
              Cancel
            </button>
            <button
              className="btn-primary"
              disabled={!draft.name?.trim() || !!validateTemplateParams(draft.templateParams) || saveEdit.isPending}
              onClick={() => saveEdit.mutate()}
            >
              Save changes
            </button>
          </div>
        </div>
      ) : (
        <div className="space-y-6">
          {status === "rejected" && c.rejectionReason && (
            <div className="rounded-xl px-4 py-3 text-sm" style={{ backgroundColor: "rgb(208 59 59 / 0.08)", color: "var(--critical)" }}>
              <p className="font-semibold">Sent back by the reviewer</p>
              <p className="mt-0.5" style={{ color: "var(--ink-secondary)" }}>
                {c.rejectionReason}
              </p>
            </div>
          )}
          {status === "submitted" && (
            <div className="rounded-xl px-4 py-3 text-sm flex items-start gap-2" style={{ backgroundColor: "rgb(201 133 0 / 0.08)" }}>
              <Clock size={15} className="mt-0.5 shrink-0" style={{ color: "var(--warning)" }} />
              <span style={{ color: "var(--ink-secondary)" }}>
                Waiting for a Super Admin or VP Sales other than the creator to approve
                {c.submittedAt ? ` · submitted ${formatRelative(c.submittedAt)}` : ""}.
              </span>
            </div>
          )}

          <div className="grid grid-cols-2 sm:grid-cols-5 gap-2.5">
            {[
              { label: "Recipients", value: progress.total, color: "var(--ink-primary)" },
              { label: "Sent", value: c.sentCount, color: "var(--brand-600)" },
              { label: "Delivered", value: c.deliveredCount, color: "var(--good)" },
              { label: "Read", value: c.readCount, color: "var(--good)" },
              { label: "Failed", value: c.failedCount, color: c.failedCount ? "var(--critical)" : "var(--ink-muted)" },
            ].map((s) => (
              <div key={s.label} className="glass-solid rounded-xl px-3 py-2.5">
                <p className="text-lg font-bold tabular-nums leading-tight" style={{ color: s.color }}>
                  {s.value.toLocaleString()}
                </p>
                <p className="text-[11px] mt-0.5" style={{ color: "var(--ink-muted)" }}>
                  {s.label}
                </p>
              </div>
            ))}
          </div>

          {["scheduled", "sending", "paused", "completed", "cancelled"].includes(status) && (
            <div>
              <div className="flex items-center justify-between mb-1.5 text-xs" style={{ color: "var(--ink-muted)" }}>
                <span>
                  {progress.processed.toLocaleString()} of {progress.total.toLocaleString()} processed
                </span>
                <span className="tabular-nums font-semibold" style={{ color: "var(--ink-secondary)" }}>
                  {progress.percent}%
                </span>
              </div>
              <CampaignProgressBar progress={progress} />
              {status === "scheduled" && c.scheduledAt && (
                <p className="text-xs mt-2" style={{ color: "var(--ink-muted)" }}>
                  Starts {formatDateTime(c.scheduledAt)}
                </p>
              )}
            </div>
          )}

          {actions.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {actions.includes("edit") && (
                <button className="btn-secondary" disabled={busy} onClick={() => runAction("edit")}>
                  <Pencil size={14} /> Edit
                </button>
              )}
              {actions
                .filter((a): a is Exclude<CampaignAction, "edit" | "delete"> => a !== "edit" && a !== "delete")
                .map((a) => {
                  const m = ACTION_META[a];
                  const label = a === "launch" && status === "draft" ? "Launch without approval" : m.label;
                  return (
                    <button
                      key={a}
                      className={m.tone}
                      style={a === "cancel" ? { color: "var(--critical)" } : undefined}
                      disabled={busy || (a === "submit" && c.type === "whatsapp" && !c.templateName)}
                      title={a === "submit" && c.type === "whatsapp" && !c.templateName ? "Add a template name first" : undefined}
                      onClick={() => runAction(a)}
                    >
                      <m.icon size={14} /> {label}
                    </button>
                  );
                })}
              {actions.includes("delete") && (
                <button className="btn-ghost ml-auto" style={{ color: "var(--critical)" }} disabled={busy} onClick={() => runAction("delete")}>
                  <Trash2 size={14} /> Delete
                </button>
              )}
            </div>
          )}

          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-3 text-sm">
            {c.type === "whatsapp" && (
              <>
                <Meta label="Template" value={c.templateName ? `${c.templateName} · ${c.templateLanguage ?? "en_US"}` : "—"} mono />
                <Meta label="Scheduled" value={c.scheduledAt ? formatDateTime(c.scheduledAt) : "On launch"} />
              </>
            )}
            <Meta label="Created" value={formatDateTime(c.createdAt)} />
            {c.approvedAt && <Meta label="Approved" value={formatDateTime(c.approvedAt)} />}
            {c.startedAt && <Meta label="Started" value={formatDateTime(c.startedAt)} />}
            {c.completedAt && <Meta label={status === "cancelled" ? "Cancelled" : "Completed"} value={formatDateTime(c.completedAt)} />}
          </dl>

          {c.messageBody && (
            <div>
              <p className="field-label">Notes</p>
              <p className="text-sm whitespace-pre-wrap p-3 rounded-xl glass-solid" style={{ color: "var(--ink-secondary)" }}>
                {c.messageBody}
              </p>
            </div>
          )}

          {editableRecipients && (
            <div className="space-y-4">
              <AudienceBuilder
                campaignId={c.id}
                initialCriteria={c.audienceCriteriaJson}
                editable
                onApplied={(r) => {
                  setLastAdd(r);
                  toast.success(`${r.added} recipient${r.added === 1 ? "" : "s"} added from leads`);
                  refresh();
                }}
              />
              <div className="glass-solid rounded-xl p-4 space-y-2">
                <p className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                  Add numbers manually
                </p>
                <textarea
                  className="textarea font-mono text-[13px]"
                  rows={4}
                  value={recipients}
                  onChange={(e) => setRecipients(e.target.value)}
                  placeholder={"+919876543210\n+14155550123"}
                />
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-xs" style={{ color: "var(--ink-muted)" }}>
                    One per line, international format. Duplicates and invalid numbers are skipped.
                  </span>
                  <button className="btn-secondary" disabled={!recipients.trim() || addRecipients.isPending} onClick={() => addRecipients.mutate()}>
                    <Plus size={14} /> Add recipients
                  </button>
                </div>
              </div>
              {lastAdd && (
                <div className="flex flex-wrap gap-2 text-xs" role="status">
                  <span className="badge-good">{lastAdd.added} added</span>
                  {lastAdd.duplicates > 0 && <span className="badge-neutral">{lastAdd.duplicates} duplicates skipped</span>}
                  {lastAdd.invalid > 0 && <span className="badge-warning">{lastAdd.invalid} invalid</span>}
                  {(lastAdd.optedOut ?? 0) > 0 && <span className="badge-critical">{lastAdd.optedOut} opted out — will be skipped</span>}
                  <span className="badge-brand">{lastAdd.total} total</span>
                </div>
              )}
            </div>
          )}

          <div>
            <div className="flex items-center justify-between gap-2 mb-2">
              <p className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                Recipients
              </p>
              <select
                className="select !w-auto text-xs"
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">All statuses</option>
                {Object.keys(RECIPIENT_TONE).map((s) => (
                  <option key={s} value={s}>
                    {s.replace(/_/g, " ")} {progress.counts[s] ? `(${progress.counts[s]})` : ""}
                  </option>
                ))}
              </select>
            </div>
            {recipientsPage.isError ? (
              <QueryError compact error={recipientsPage.error} onRetry={() => recipientsPage.refetch()} />
            ) : recipientsPage.isLoading ? (
              <Skeleton className="h-32 w-full" />
            ) : (recipientsPage.data ?? []).length === 0 ? (
              <p className="text-sm py-6 text-center" style={{ color: "var(--ink-muted)" }}>
                {statusFilter ? "No recipients with this status." : "No recipients yet."}
              </p>
            ) : (
              <div className="rounded-xl hairline border overflow-hidden">
                <ul>
                  {recipientsPage.data!.map((r) => (
                    <li key={r.id} className="flex items-center gap-3 px-3 py-2 hairline border-b last:border-b-0 text-sm">
                      <div className="min-w-0 flex-1">
                        <p className="font-mono text-[13px] tabular-nums truncate" style={{ color: "var(--ink-primary)" }}>
                          {r.normalizedPhone ?? r.phoneNumber}
                        </p>
                        {(r.name || r.failedReason) && (
                          <p className="text-[11px] truncate" style={{ color: r.failedReason ? "var(--critical)" : "var(--ink-muted)" }}>
                            {r.failedReason ?? r.name}
                          </p>
                        )}
                      </div>
                      <span className={RECIPIENT_TONE[r.status] ?? "badge-neutral"}>{r.status.replace(/_/g, " ")}</span>
                      {editableRecipients && (
                        <button className="btn-icon h-7 w-7" aria-label="Remove recipient" onClick={() => removeRecipient.mutate(r.id)}>
                          <Trash2 size={13} />
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {(page > 0 || (recipientsPage.data?.length ?? 0) === PAGE) && (
              <div className="flex items-center justify-end gap-2 mt-2">
                <button className="btn-icon h-8 w-8" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))} aria-label="Previous page">
                  <ChevronLeft size={15} />
                </button>
                <span className="text-xs tabular-nums" style={{ color: "var(--ink-muted)" }}>
                  Page {page + 1}
                </span>
                <button
                  className="btn-icon h-8 w-8"
                  disabled={(recipientsPage.data?.length ?? 0) < PAGE}
                  onClick={() => setPage((p) => p + 1)}
                  aria-label="Next page"
                >
                  <ChevronRight size={15} />
                </button>
              </div>
            )}
          </div>
        </div>
      )}
      {confirmNode}
      {reasonNode}
    </Drawer>
  );
}

function Meta({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <dt className="text-[11px] uppercase tracking-wide font-semibold" style={{ color: "var(--ink-muted)" }}>
        {label}
      </dt>
      <dd className={`mt-0.5 ${mono ? "font-mono text-[13px]" : ""}`} style={{ color: "var(--ink-primary)" }}>
        {value}
      </dd>
    </div>
  );
}
