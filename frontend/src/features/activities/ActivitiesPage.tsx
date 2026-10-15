import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity as ActivityIcon,
  CornerDownRight,
  Linkedin,
  Mail,
  MessageCircle,
  Phone,
  Plus,
  StickyNote,
  Video,
} from "lucide-react";
import { useState } from "react";
import { api, getList } from "@/lib/api";
import { useUsersLookup } from "@/lib/hooks";
import { formatDateTime, formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import type { Activity } from "@/lib/types";
import type { LucideIcon } from "lucide-react";
import { PageHeader } from "@/components/layout/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { EmptyState } from "@/components/ui/EmptyState";
import { Modal } from "@/components/ui/Modal";
import { Field, FieldGrid } from "@/components/ui/Field";
import { SkeletonRows } from "@/components/ui/Skeleton";

const TYPES = ["call", "email", "meeting", "note", "whatsapp", "linkedin", "task", "stage_change"];
const RELATED_TYPES = ["Lead", "Opportunity", "Contact", "Company"];
const TYPE_ICON: Record<string, LucideIcon> = {
  call: Phone,
  email: Mail,
  meeting: Video,
  note: StickyNote,
  whatsapp: MessageCircle,
  linkedin: Linkedin,
  task: ActivityIcon,
  stage_change: ActivityIcon,
};

export default function ActivitiesPage() {
  const [typeFilter, setTypeFilter] = useState<string>("");
  const [composerOpen, setComposerOpen] = useState(false);
  const [correctingId, setCorrectingId] = useState<number | null>(null);
  const [form, setForm] = useState<Partial<Activity>>({ type: "note" });
  const users = useUsersLookup();
  const qc = useQueryClient();

  const list = useQuery({
    queryKey: ["activities"],
    queryFn: () => getList<Activity>("/activities", { limit: 300 }),
  });

  const userName = (id: number | null) => users.data?.find((u) => u.id === id)?.name ?? "Someone";

  const filtered = (list.data?.data ?? []).filter((a) => !typeFilter || a.type === typeFilter);

  async function submit() {
    try {
      if (correctingId) {
        await api.post(`/activities/${correctingId}/correct`, form);
        toast.success("Correction logged");
      } else {
        await api.post("/activities", form);
        toast.success("Activity logged");
      }
      qc.invalidateQueries({ queryKey: ["activities"] });
      setComposerOpen(false);
      setCorrectingId(null);
      setForm({ type: "note" });
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "Could not save activity");
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Activity Timeline"
        description="An append-only record of every call, email, meeting and note."
        icon={<ActivityIcon size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setComposerOpen(true)}>
            <Plus size={15} /> Log activity
          </button>
        }
      />

      <div className="flex items-center gap-2 overflow-x-auto thin-scroll pb-1">
        <button
          className={typeFilter === "" ? "btn-primary px-3 py-1.5 text-xs" : "btn-secondary px-3 py-1.5 text-xs"}
          onClick={() => setTypeFilter("")}
        >
          All
        </button>
        {TYPES.map((t) => (
          <button
            key={t}
            className={typeFilter === t ? "btn-primary px-3 py-1.5 text-xs capitalize" : "btn-secondary px-3 py-1.5 text-xs capitalize"}
            onClick={() => setTypeFilter(t)}
          >
            {t.replace("_", " ")}
          </button>
        ))}
      </div>

      {list.isLoading ? (
        <div className="glass-card">
          <SkeletonRows rows={8} cols={1} />
        </div>
      ) : filtered.length === 0 ? (
        <div className="glass-card">
          <EmptyState icon={ActivityIcon} title="No activity yet" description="Log calls, emails, meetings and notes as you work records." />
        </div>
      ) : (
        <div className="relative pl-6">
          <div className="absolute left-[9px] top-2 bottom-2 w-px" style={{ backgroundColor: "rgb(var(--panel-border) / 0.12)" }} />
          <div className="space-y-4">
            {filtered.map((a) => {
              const Icon = TYPE_ICON[a.type] ?? ActivityIcon;
              return (
                <div key={a.id} className="relative">
                  <div
                    className="absolute -left-6 top-1 flex items-center justify-center h-5 w-5 rounded-full ring-4"
                    style={{ backgroundColor: "var(--brand-500)", ["--tw-ring-color" as string]: "var(--plane)" } as React.CSSProperties}
                  >
                    <Icon size={11} className="text-white" />
                  </div>
                  <div className="glass-card p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex items-center gap-2.5 min-w-0">
                        <Avatar name={userName(a.userId)} size={26} />
                        <div className="min-w-0">
                          <p className="text-sm font-medium truncate" style={{ color: "var(--ink-primary)" }}>
                            {a.subject || `${a.type.replace("_", " ")} logged`}
                          </p>
                          <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                            {userName(a.userId)} · {formatRelative(a.createdAt)}
                            {a.relatedType && ` · ${a.relatedType} #${a.relatedId}`}
                          </p>
                        </div>
                      </div>
                      <span className="badge-brand capitalize shrink-0">{a.type.replace("_", " ")}</span>
                    </div>
                    {a.body && (
                      <p className="text-sm mt-2.5 whitespace-pre-wrap" style={{ color: "var(--ink-secondary)" }}>
                        {a.body}
                      </p>
                    )}
                    {a.masked && (
                      <p className="text-xs italic mt-2" style={{ color: "var(--ink-muted)" }}>
                        Full content hidden — you don't have access to this entry's details.
                      </p>
                    )}
                    <div className="flex items-center justify-between mt-3">
                      <span className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
                        {formatDateTime(a.createdAt)}
                        {a.correctionForId && (
                          <span className="ml-2 inline-flex items-center gap-1">
                            <CornerDownRight size={11} /> correction for #{a.correctionForId}
                          </span>
                        )}
                      </span>
                      {!a.correctionForId && (
                        <button
                          className="text-xs font-semibold"
                          style={{ color: "var(--brand-600)" }}
                          onClick={() => {
                            setCorrectingId(a.id);
                            setForm({ type: a.type });
                            setComposerOpen(true);
                          }}
                        >
                          Add correction
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      <Modal
        open={composerOpen}
        onClose={() => {
          setComposerOpen(false);
          setCorrectingId(null);
        }}
        title={correctingId ? `Correct activity #${correctingId}` : "Log activity"}
        width="30rem"
        footer={
          <>
            <button className="btn-ghost" onClick={() => setComposerOpen(false)}>
              Cancel
            </button>
            <button className="btn-primary" onClick={submit}>
              {correctingId ? "Save correction" : "Log activity"}
            </button>
          </>
        }
      >
        <div className="space-y-4">
          <FieldGrid>
            <Field label="Type">
              <select className="select" value={form.type ?? "note"} onChange={(e) => setForm((f) => ({ ...f, type: e.target.value }))}>
                {TYPES.map((t) => (
                  <option key={t} value={t} className="capitalize">
                    {t.replace("_", " ")}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Outcome">
              <input className="input" value={form.outcome ?? ""} onChange={(e) => setForm((f) => ({ ...f, outcome: e.target.value }))} />
            </Field>
            {!correctingId && (
              <>
                <Field label="Related type">
                  <select
                    className="select"
                    value={form.relatedType ?? ""}
                    onChange={(e) => setForm((f) => ({ ...f, relatedType: e.target.value }))}
                  >
                    <option value="">—</option>
                    {RELATED_TYPES.map((t) => (
                      <option key={t}>{t}</option>
                    ))}
                  </select>
                </Field>
                <Field label="Related ID">
                  <input
                    className="input"
                    type="number"
                    value={form.relatedId ?? ""}
                    onChange={(e) => setForm((f) => ({ ...f, relatedId: e.target.value ? Number(e.target.value) : undefined }))}
                  />
                </Field>
              </>
            )}
          </FieldGrid>
          <Field label="Subject">
            <input className="input" value={form.subject ?? ""} onChange={(e) => setForm((f) => ({ ...f, subject: e.target.value }))} />
          </Field>
          <Field label="Details">
            <textarea className="textarea" value={form.body ?? ""} onChange={(e) => setForm((f) => ({ ...f, body: e.target.value }))} />
          </Field>
        </div>
      </Modal>
    </div>
  );
}
