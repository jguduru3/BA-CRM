import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Play, Plus, Trash2, Workflow, Zap } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { EmptyState } from "@/components/ui/EmptyState";
import { Drawer } from "@/components/ui/Drawer";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";
import { SkeletonCards } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

interface Automation {
  id: number;
  name: string;
  description: string | null;
  triggerType: string;
  actionType: string;
  actionConfigJson: string | null;
  apiProvider: string;
  apiKeyEncrypted: string | null;
  modelId: string | null;
  active: boolean;
  runCount: number;
  lastRunAt: string | null;
}
interface Run {
  id: number;
  status: string;
  outputText: string | null;
  errorMessage: string | null;
  startedAt: string;
}

const TRIGGERS = [
  { value: "manual", label: "Manual only" },
  { value: "on_lead_created", label: "When a lead is created" },
  { value: "on_stage_change", label: "When a lead or deal changes stage" },
  { value: "on_campaign_approved", label: "When a campaign is approved" },
  { value: "scheduled", label: "Scheduled (cron — stored, not yet auto-run)" },
  { value: "webhook", label: "Webhook (stored, not yet exposed)" },
];
const ACTIONS = [
  { value: "generate_content", label: "Generate content" },
  { value: "prepare_strategy", label: "Prepare strategy" },
  { value: "analyze_data", label: "Analyze data" },
  { value: "send_email", label: "Send email" },
  { value: "read_email", label: "Read and summarize email" },
  { value: "custom_api", label: "Call a custom API" },
];

const CONFIG_PLACEHOLDER = `{
  "systemPrompt": "You are a professional CRM AI assistant.",
  "promptTemplate": "Draft a follow-up for {{lead.name}} in stage {{lead.stage}}.",
  "maxTokens": 1000
}`;

export default function AutomationsPage() {
  const [selected, setSelected] = useState<Automation | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<Partial<Automation>>({});
  const [runs, setRuns] = useState<Run[]>([]);
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["ai-automations"],
    queryFn: async () => (await api.get<Automation[]>("/ai-automations")).data,
  });

  useEffect(() => {
    setDraft(selected ?? { triggerType: "manual", actionType: "generate_content", apiProvider: "anthropic", active: true });
    setRuns([]);
    if (selected) {
      api.get<Run[]>(`/ai-automations/${selected.id}/runs`).then((r) => setRuns(r.data));
    }
  }, [selected, creating]);

  const save = useMutation({
    mutationFn: async () => {
      if (selected) return api.put(`/ai-automations/${selected.id}`, draft);
      return api.post("/ai-automations", draft);
    },
    onSuccess: () => {
      toast.success(selected ? "Automation updated" : "Automation created");
      qc.invalidateQueries({ queryKey: ["ai-automations"] });
      setSelected(null);
      setCreating(false);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/ai-automations/${id}`),
    onSuccess: () => {
      toast.success("Automation deleted");
      qc.invalidateQueries({ queryKey: ["ai-automations"] });
      setSelected(null);
    },
  });

  const run = useMutation({
    mutationFn: async (id: number) => (await api.post<Run>(`/ai-automations/${id}/run`, {})).data,
    onSuccess: (newRun) => {
      toast.success(newRun.status === "completed" ? "Run completed" : "Run failed — see below");
      setRuns((r) => [newRun, ...r]);
      qc.invalidateQueries({ queryKey: ["ai-automations"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="AI Automations"
        description="Trigger → condition → action rules, powered by your configured AI provider."
        icon={<Workflow size={20} />}
        actions={
          <button className="btn-primary" onClick={() => setCreating(true)}>
            <Plus size={15} /> New automation
          </button>
        }
      />

      {list.isLoading ? (
        <SkeletonCards count={4} />
      ) : (list.data ?? []).length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={Zap}
            title="No automations yet"
            description='Create a rule like "when a lead reaches Proposal, draft a follow-up email."'
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {list.data!.map((a) => (
            <button key={a.id} onClick={() => setSelected(a)} className="glass-card-hover p-4 text-left">
              <div className="flex items-start justify-between">
                <span className={a.active ? "badge-good" : "badge-neutral"}>{a.active ? "Active" : "Paused"}</span>
                <span className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
                  {a.runCount} runs
                </span>
              </div>
              <p className="mt-2 text-sm font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                {a.name}
              </p>
              <p className="text-xs mt-1" style={{ color: "var(--ink-muted)" }}>
                {TRIGGERS.find((t) => t.value === a.triggerType)?.label} → {ACTIONS.find((x) => x.value === a.actionType)?.label}
              </p>
              {a.lastRunAt && (
                <p className="text-[11px] mt-2" style={{ color: "var(--ink-muted)" }}>
                  Last run {formatRelative(a.lastRunAt)}
                </p>
              )}
            </button>
          ))}
        </div>
      )}

      <Drawer
        open={!!selected || creating}
        onClose={() => {
          setSelected(null);
          setCreating(false);
        }}
        title={selected ? selected.name : "New automation"}
        width="34rem"
        headerExtra={
          selected && (
            <>
              <button className="btn-icon" title="Run now" onClick={() => run.mutate(selected.id)}>
                <Play size={16} />
              </button>
              <button
                className="btn-icon"
                style={{ color: "var(--critical)" }}
                onClick={async () => {
                  const ok = await confirm("Delete this automation?", `"${selected.name}" will be removed.`);
                  if (ok) remove.mutate(selected.id);
                }}
              >
                <Trash2 size={16} />
              </button>
            </>
          )
        }
      >
        <div className="space-y-4">
          <Field label="Name" required>
            <input className="input" value={draft.name ?? ""} onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))} />
          </Field>
          <Field label="Description">
            <textarea className="textarea" value={draft.description ?? ""} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
          </Field>
          <FieldGrid>
            <Field label="Trigger">
              <select className="select" value={draft.triggerType ?? "manual"} onChange={(e) => setDraft((d) => ({ ...d, triggerType: e.target.value }))}>
                {TRIGGERS.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Action">
              <select className="select" value={draft.actionType ?? "generate_content"} onChange={(e) => setDraft((d) => ({ ...d, actionType: e.target.value }))}>
                {ACTIONS.map((a) => (
                  <option key={a.value} value={a.value}>
                    {a.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="AI provider">
              <select className="select" value={draft.apiProvider ?? "anthropic"} onChange={(e) => setDraft((d) => ({ ...d, apiProvider: e.target.value }))}>
                <option value="anthropic">Anthropic (Claude)</option>
                <option value="openai">OpenAI</option>
              </select>
            </Field>
            <Field label="Model">
              <input className="input" value={draft.modelId ?? "claude-haiku-4-5-20251001"} onChange={(e) => setDraft((d) => ({ ...d, modelId: e.target.value }))} />
            </Field>
          </FieldGrid>
          <Field label="API key" hint="This automation's own key — independent of the Settings → AI Assistant key.">
            <input
              className="input"
              type="password"
              placeholder="sk-ant-…"
              value={draft.apiKeyEncrypted ?? ""}
              onChange={(e) => setDraft((d) => ({ ...d, apiKeyEncrypted: e.target.value }))}
            />
          </Field>
          <Field label="Action config (JSON)" hint="Supports {{variable}} templating from the trigger context (e.g. {{lead.name}}, {{lead.stage}}).">
            <textarea
              className="textarea font-mono text-xs"
              rows={7}
              placeholder={CONFIG_PLACEHOLDER}
              value={draft.actionConfigJson ?? ""}
              onChange={(e) => setDraft((d) => ({ ...d, actionConfigJson: e.target.value }))}
            />
          </Field>
          <label className="inline-flex items-center gap-2 text-sm">
            <input type="checkbox" className="checkbox" checked={draft.active ?? true} onChange={(e) => setDraft((d) => ({ ...d, active: e.target.checked }))} />
            Active
          </label>

          <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
            <button
              className="btn-ghost"
              onClick={() => {
                setSelected(null);
                setCreating(false);
              }}
            >
              Cancel
            </button>
            <button className="btn-primary" disabled={!draft.name} onClick={() => save.mutate()}>
              {selected ? "Save changes" : "Create automation"}
            </button>
          </div>

          {selected && (
            <div className="pt-4 hairline border-t">
              <SectionTitle>Recent runs</SectionTitle>
              {runs.length === 0 ? (
                <p className="text-xs mt-2" style={{ color: "var(--ink-muted)" }}>
                  No runs yet — click the play button above to test it.
                </p>
              ) : (
                <div className="space-y-2 mt-2">
                  {runs.map((r) => (
                    <div key={r.id} className="text-xs p-2.5 rounded-lg" style={{ backgroundColor: "rgb(var(--panel-border) / 0.05)" }}>
                      <div className="flex items-center justify-between mb-1">
                        <span className={r.status === "completed" ? "badge-good" : r.status === "failed" ? "badge-critical" : "badge-neutral"}>
                          {r.status}
                        </span>
                        <span style={{ color: "var(--ink-muted)" }}>{formatRelative(r.startedAt)}</span>
                      </div>
                      <p className="whitespace-pre-wrap" style={{ color: "var(--ink-secondary)" }}>
                        {r.outputText || r.errorMessage || "—"}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </Drawer>
      {confirmNode}
    </div>
  );
}
