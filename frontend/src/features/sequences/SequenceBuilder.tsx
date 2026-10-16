import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, CheckSquare, Clock, Mail, MessageCircle, Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { toast } from "@/lib/toast";
import { Field } from "@/components/ui/Field";
import {
  blankStep,
  type Channel,
  CHANNEL_LABEL,
  cumulativeHours,
  dayLabel,
  type Sequence,
  type SequenceStep,
  TEMPLATE_VARIABLES,
  toPayload,
  validateStep,
} from "./sequenceTypes";

export const CHANNEL_ICON: Record<Channel, typeof Mail> = {
  EMAIL: Mail,
  WHATSAPP: MessageCircle,
  TASK: CheckSquare,
};

export const CHANNEL_COLOR: Record<Channel, string> = {
  EMAIL: "var(--brand-500)",
  WHATSAPP: "var(--good)",
  TASK: "var(--warning)",
};

interface Draft {
  name: string;
  description: string;
  active: boolean;
  steps: SequenceStep[];
}

function toDraft(s: Sequence | null): Draft {
  if (!s) return { name: "", description: "", active: true, steps: [blankStep("EMAIL")] };
  return {
    name: s.name,
    description: s.description ?? "",
    active: s.active,
    steps: s.steps.map((x) => ({ ...x, waTemplateParams: x.waTemplateParams ?? [] })),
  };
}

/** Create / edit a sequence: name, steps (channel, delay from previous step, content). */
export function SequenceBuilder({
  sequence,
  onSaved,
  onCancel,
}: {
  sequence: Sequence | null;
  onSaved: (s: Sequence) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<Draft>(() => toDraft(sequence));
  const qc = useQueryClient();
  const offsets = cumulativeHours(draft.steps);
  const problems = draft.steps.map(validateStep);
  const invalid = !draft.name.trim() || draft.steps.length === 0 || problems.some((p) => p !== null);

  const save = useMutation({
    mutationFn: async () => {
      const body = toPayload(draft);
      return sequence
        ? (await api.put<Sequence>(`/sequences/${sequence.id}`, body)).data
        : (await api.post<Sequence>("/sequences", body)).data;
    },
    onSuccess: (s) => {
      toast.success(sequence ? "Sequence saved" : "Sequence created");
      qc.invalidateQueries({ queryKey: ["sequences"] });
      onSaved(s);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const setStep = (i: number, patch: Partial<SequenceStep>) =>
    setDraft((d) => ({ ...d, steps: d.steps.map((s, j) => (j === i ? { ...s, ...patch } : s)) }));
  const move = (i: number, dir: -1 | 1) =>
    setDraft((d) => {
      const steps = [...d.steps];
      const j = i + dir;
      if (j < 0 || j >= steps.length) return d;
      [steps[i], steps[j]] = [steps[j], steps[i]];
      return { ...d, steps };
    });
  const remove = (i: number) => setDraft((d) => ({ ...d, steps: d.steps.filter((_, j) => j !== i) }));
  const add = (channel: Channel) =>
    setDraft((d) => ({ ...d, steps: [...d.steps, { ...blankStep(channel), delayDays: d.steps.length ? 2 : 0 }] }));

  return (
    <div className="space-y-5">
      <div className="space-y-4">
        <Field label="Name" required>
          <input
            className="input"
            value={draft.name}
            maxLength={200}
            placeholder="e.g. New inbound lead follow-up"
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
          />
        </Field>
        <Field label="Description">
          <textarea
            className="textarea"
            rows={2}
            maxLength={1000}
            value={draft.description}
            placeholder="Who this is for and what it should achieve"
            onChange={(e) => setDraft({ ...draft, description: e.target.value })}
          />
        </Field>
        <label className="flex items-center gap-2 text-sm cursor-pointer" style={{ color: "var(--ink-secondary)" }}>
          <input type="checkbox" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />
          Active — enrolled records receive steps
        </label>
      </div>

      <div
        className="rounded-xl p-3 text-xs leading-relaxed"
        style={{ backgroundColor: "rgb(var(--ring-brand) / 0.06)", color: "var(--ink-secondary)" }}
      >
        Any reply (WhatsApp, or email seen in the inbox) stops the sequence. Steps whose channel the record can't receive —
        no email, no phone, opted out of WhatsApp — are skipped, and after a bounce or undeliverable message the rest of that
        channel is skipped. Variables: {TEMPLATE_VARIABLES.join(" ")}
      </div>

      <ol className="space-y-3">
        {draft.steps.map((s, i) => {
          const Icon = CHANNEL_ICON[s.channel];
          return (
            <li key={i} className="glass-card p-4 space-y-3" aria-label={`Step ${i + 1}`}>
              <div className="flex flex-wrap items-center gap-2">
                <span
                  className="flex items-center justify-center h-7 w-7 rounded-lg shrink-0"
                  style={{ backgroundColor: "rgb(var(--panel-border) / 0.08)", color: CHANNEL_COLOR[s.channel] }}
                >
                  <Icon size={15} />
                </span>
                <span className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                  Step {i + 1}
                </span>
                <span className="text-[11px] tabular-nums" style={{ color: "var(--ink-muted)" }}>
                  {dayLabel(offsets[i])}
                </span>
                <div className="ml-auto flex items-center gap-1">
                  <button className="btn-icon" aria-label="Move up" disabled={i === 0} onClick={() => move(i, -1)}>
                    <ArrowUp size={14} />
                  </button>
                  <button className="btn-icon" aria-label="Move down" disabled={i === draft.steps.length - 1} onClick={() => move(i, 1)}>
                    <ArrowDown size={14} />
                  </button>
                  <button className="btn-icon" aria-label="Remove step" onClick={() => remove(i)}>
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <Field label="Channel">
                  <select
                    className="input"
                    value={s.channel}
                    onChange={(e) => setStep(i, { channel: e.target.value as Channel })}
                  >
                    {(Object.keys(CHANNEL_LABEL) as Channel[]).map((c) => (
                      <option key={c} value={c}>
                        {CHANNEL_LABEL[c]}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label={i === 0 ? "Wait (days) after enrolling" : "Wait (days) after previous"}>
                  <input
                    className="input"
                    type="number"
                    min={0}
                    max={365}
                    value={s.delayDays}
                    onChange={(e) => setStep(i, { delayDays: Math.max(0, parseInt(e.target.value || "0", 10) || 0) })}
                  />
                </Field>
                <Field label="+ hours">
                  <input
                    className="input"
                    type="number"
                    min={0}
                    max={23}
                    value={s.delayHours}
                    onChange={(e) => setStep(i, { delayHours: Math.max(0, parseInt(e.target.value || "0", 10) || 0) })}
                  />
                </Field>
              </div>

              {s.channel === "WHATSAPP" ? (
                <div className="space-y-3">
                  <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                    <Field label="Approved template" className="sm:col-span-2" required>
                      <input
                        className="input"
                        value={s.waTemplateName ?? ""}
                        placeholder="follow_up_v1"
                        onChange={(e) => setStep(i, { waTemplateName: e.target.value })}
                      />
                    </Field>
                    <Field label="Language">
                      <input
                        className="input"
                        value={s.waTemplateLanguage ?? ""}
                        placeholder="en_US"
                        onChange={(e) => setStep(i, { waTemplateLanguage: e.target.value })}
                      />
                    </Field>
                  </div>
                  <Field label="Body parameters" hint="One per line, in template order ({{1}}, {{2}}, …). Variables allowed.">
                    <textarea
                      className="textarea"
                      rows={2}
                      value={(s.waTemplateParams ?? []).join("\n")}
                      placeholder="{{record.firstName}}"
                      onChange={(e) => setStep(i, { waTemplateParams: e.target.value.split("\n") })}
                    />
                  </Field>
                </div>
              ) : (
                <div className="space-y-3">
                  <Field label={s.channel === "TASK" ? "Task title" : "Subject"} required>
                    <input
                      className="input"
                      value={s.subject ?? ""}
                      maxLength={300}
                      placeholder={s.channel === "TASK" ? "Call {{record.name}}" : "Quick question, {{record.firstName}}"}
                      onChange={(e) => setStep(i, { subject: e.target.value })}
                    />
                  </Field>
                  {s.channel === "EMAIL" && (
                    <Field label="Body" required hint="Sent as plain text from the sequence mailbox (or the owner's own).">
                      <textarea
                        className="textarea"
                        rows={5}
                        value={s.body ?? ""}
                        placeholder={"Hi {{record.firstName}},\n\n…\n\n{{owner.name}}"}
                        onChange={(e) => setStep(i, { body: e.target.value })}
                      />
                    </Field>
                  )}
                </div>
              )}

              {problems[i] && (
                <p className="text-xs" role="alert" style={{ color: "var(--critical)" }}>
                  {problems[i]}
                </p>
              )}
            </li>
          );
        })}
      </ol>

      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs inline-flex items-center gap-1" style={{ color: "var(--ink-muted)" }}>
          <Clock size={12} /> Add step:
        </span>
        {(Object.keys(CHANNEL_LABEL) as Channel[]).map((c) => {
          const Icon = CHANNEL_ICON[c];
          return (
            <button key={c} className="btn-secondary" disabled={draft.steps.length >= 30} onClick={() => add(c)}>
              <Plus size={13} /> <Icon size={13} /> {CHANNEL_LABEL[c]}
            </button>
          );
        })}
      </div>

      <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
        <button className="btn-ghost" onClick={onCancel}>
          Cancel
        </button>
        <button className="btn-primary" disabled={invalid || save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? "Saving…" : sequence ? "Save sequence" : "Create sequence"}
        </button>
      </div>
    </div>
  );
}
