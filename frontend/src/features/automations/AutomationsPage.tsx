import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarClock, Copy, Hand, Play, Plus, RefreshCw, Trash2, Webhook, Workflow, Zap } from "lucide-react";
import { useState } from "react";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { formatDateTime, formatRelative } from "@/lib/format";
import { toast } from "@/lib/toast";
import { PageHeader } from "@/components/layout/PageHeader";
import { Drawer } from "@/components/ui/Drawer";
import { EmptyState } from "@/components/ui/EmptyState";
import { Modal } from "@/components/ui/Modal";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton, SkeletonCards } from "@/components/ui/Skeleton";
import { Tabs } from "@/components/ui/Tabs";
import { useConfirm } from "@/components/ui/ConfirmDialog";
import { AutomationEditor, type EditorState } from "./AutomationEditor";
import {
  ACTIONS,
  type Automation,
  type AutomationRun,
  formatDuration,
  looksLikeCron,
  parseJsonObject,
  serializeConfig,
  serializeTrigger,
  TRIGGERS,
} from "./automationConfig";

const NEW_STATE: EditorState = {
  name: "",
  description: "",
  triggerType: "manual",
  trigger: { cron: "0 9 * * MON-FRI", timezone: "UTC" },
  actionType: "generate_content",
  config: { promptTemplate: "" },
  apiProvider: "anthropic",
  modelId: "claude-sonnet-5-5",
  apiKey: "",
  clearApiKey: false,
  active: true,
};

function toState(a: Automation): EditorState {
  const trig = parseJsonObject(a.triggerConfigJson);
  return {
    name: a.name,
    description: a.description ?? "",
    triggerType: a.triggerType,
    trigger: { cron: typeof trig.cron === "string" ? trig.cron : "", timezone: typeof trig.timezone === "string" ? trig.timezone : "UTC" },
    actionType: a.actionType,
    config: parseJsonObject(a.actionConfigJson),
    apiProvider: a.apiProvider === "openai" ? "openai" : "anthropic",
    modelId: a.modelId ?? "",
    apiKey: "",
    clearApiKey: false,
    active: a.active,
  };
}

const TRIGGER_ICON = { manual: Hand, scheduled: CalendarClock, webhook: Webhook } as Record<string, typeof Zap>;

function RunsLog({ automationId }: { automationId: number }) {
  const runs = useQuery({
    queryKey: ["ai-automations", "runs", automationId],
    queryFn: async () => (await api.get<AutomationRun[]>(`/ai-automations/${automationId}/runs`, { params: { limit: 50 } })).data,
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === "running") ? 3_000 : false),
  });
  const [open, setOpen] = useState<number | null>(null);

  if (runs.isError) return <QueryError compact error={runs.error} onRetry={() => runs.refetch()} title="Couldn't load the run log" />;
  if (runs.isLoading) return <Skeleton className="h-24 w-full" />;
  if ((runs.data ?? []).length === 0)
    return (
      <p className="text-sm py-4 text-center" style={{ color: "var(--ink-muted)" }}>
        No runs yet — use Run now to test it.
      </p>
    );
  return (
    <div className="rounded-xl hairline border overflow-hidden">
      <ul>
        {runs.data!.map((r) => {
          const tone = r.status === "completed" ? "badge-good" : r.status === "failed" ? "badge-critical" : "badge-brand";
          const text = r.status === "failed" ? r.errorMessage : r.outputText;
          return (
            <li key={r.id} className="hairline border-b last:border-b-0">
              <button className="w-full text-left px-3 py-2.5" onClick={() => setOpen((o) => (o === r.id ? null : r.id))}>
                <div className="flex items-center gap-2 text-xs">
                  <span className={tone}>{r.status}</span>
                  <span className="capitalize" style={{ color: "var(--ink-secondary)" }}>
                    {r.triggerSource ?? "manual"}
                  </span>
                  <span className="ml-auto tabular-nums" style={{ color: "var(--ink-muted)" }}>
                    {formatDuration(r.durationMs)}
                  </span>
                  <span className="tabular-nums whitespace-nowrap" style={{ color: "var(--ink-muted)" }} title={formatDateTime(r.startedAt)}>
                    {formatRelative(r.startedAt)}
                  </span>
                </div>
                {text && (
                  <p
                    className={`text-xs mt-1.5 whitespace-pre-wrap break-words ${open === r.id ? "" : "line-clamp-2"}`}
                    style={{ color: r.status === "failed" ? "var(--critical)" : "var(--ink-secondary)" }}
                  >
                    {text}
                  </p>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export default function AutomationsPage() {
  const user = useAuthStore((s) => s.user);
  const privileged = user?.role === "SUPER_ADMIN" || user?.role === "VP_SALES";
  const [selected, setSelected] = useState<Automation | null>(null);
  const [creating, setCreating] = useState(false);
  const [state, setState] = useState<EditorState>(NEW_STATE);
  const [drawerTab, setDrawerTab] = useState<"setup" | "runs">("setup");
  const [secret, setSecret] = useState<{ secret: string; hookPath: string | null | undefined } | null>(null);
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();

  const list = useQuery({
    queryKey: ["ai-automations"],
    queryFn: async () => (await api.get<Automation[]>("/ai-automations")).data,
  });

  const openNew = () => {
    setSelected(null);
    setState(NEW_STATE);
    setDrawerTab("setup");
    setCreating(true);
  };
  const openExisting = (a: Automation) => {
    setCreating(false);
    setSelected(a);
    setState(toState(a));
    setDrawerTab("setup");
  };
  const close = () => {
    setSelected(null);
    setCreating(false);
  };

  const save = useMutation({
    mutationFn: async () => {
      const body: Record<string, unknown> = {
        name: state.name.trim(),
        description: state.description,
        triggerType: state.triggerType,
        triggerConfigJson: serializeTrigger(state.triggerType, state.trigger),
        actionType: state.actionType,
        actionConfigJson: serializeConfig(state.config),
        apiProvider: state.apiProvider,
        modelId: state.modelId.trim() || undefined,
        active: state.active,
      };
      if (state.apiKey.trim()) body.apiKey = state.apiKey.trim();
      if (state.clearApiKey) body.clearApiKey = true;
      return selected
        ? (await api.put<Automation>(`/ai-automations/${selected.id}`, body)).data
        : (await api.post<Automation>("/ai-automations", body)).data;
    },
    onSuccess: (a) => {
      toast.success(selected ? "Automation saved" : "Automation created");
      qc.invalidateQueries({ queryKey: ["ai-automations"] });
      if (a.webhookSecret) setSecret({ secret: a.webhookSecret, hookPath: a.hookPath });
      setCreating(false);
      setSelected(a);
      setState(toState(a));
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const remove = useMutation({
    mutationFn: async (id: number) => api.delete(`/ai-automations/${id}`),
    onSuccess: () => {
      toast.success("Automation deleted");
      qc.invalidateQueries({ queryKey: ["ai-automations"] });
      close();
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const run = useMutation({
    mutationFn: async (id: number) => (await api.post<AutomationRun>(`/ai-automations/${id}/run`, {})).data,
    onSuccess: (r) => {
      if (r.status === "completed") toast.success(`Run completed in ${formatDuration(r.durationMs)}`);
      else toast.error(r.errorMessage ?? "Run failed — see the run log");
      qc.invalidateQueries({ queryKey: ["ai-automations"] });
      setDrawerTab("runs");
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const rotate = useMutation({
    mutationFn: async (id: number) => (await api.post<{ webhookSecret: string; hookPath: string }>(`/ai-automations/${id}/webhook-secret`, {})).data,
    onSuccess: (r) => setSecret({ secret: r.webhookSecret, hookPath: r.hookPath }),
    onError: (e: Error) => toast.error(e.message),
  });

  const valid =
    state.name.trim().length > 0 &&
    (state.triggerType !== "scheduled" || looksLikeCron(state.trigger.cron)) &&
    (!(state.actionType === "send_email" || state.actionType === "read_email") || typeof state.config.emailAccountId === "number") &&
    (state.actionType !== "custom_api" || (typeof state.config.url === "string" && state.config.url.trim() !== ""));

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="AI Automations"
        description="When something happens, let AI draft, analyse or call your systems — every run is logged."
        icon={<Workflow size={20} />}
        actions={
          <button className="btn-primary" onClick={openNew}>
            <Plus size={15} /> New automation
          </button>
        }
      />

      {list.isError ? (
        <div className="glass-card">
          <QueryError error={list.error} onRetry={() => list.refetch()} title="Couldn't load automations" />
        </div>
      ) : list.isLoading ? (
        <SkeletonCards count={4} />
      ) : (list.data ?? []).length === 0 ? (
        <div className="glass-card">
          <EmptyState
            icon={Zap}
            title="No automations yet"
            description='Create a rule like "every weekday at 9:00, summarise yesterday’s new leads".'
            action={
              <button className="btn-primary" onClick={openNew}>
                <Plus size={15} /> New automation
              </button>
            }
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-4">
          {list.data!.map((a) => {
            const Icon = TRIGGER_ICON[a.triggerType] ?? Zap;
            return (
              <button key={a.id} onClick={() => openExisting(a)} className="glass-card-hover p-4 text-left flex flex-col gap-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center justify-center h-9 w-9 rounded-xl" style={{ backgroundColor: "rgb(var(--ring-brand) / 0.1)" }}>
                    <Icon size={16} style={{ color: "var(--brand-600)" }} />
                  </div>
                  <span className={a.active ? "badge-good" : "badge-neutral"}>{a.active ? "Active" : "Paused"}</span>
                </div>
                <div className="min-w-0">
                  <p className="text-sm font-semibold truncate" style={{ color: "var(--ink-primary)" }}>
                    {a.name}
                  </p>
                  <p className="text-xs mt-1" style={{ color: "var(--ink-muted)" }}>
                    {TRIGGERS.find((t) => t.value === a.triggerType)?.label ?? a.triggerType} →{" "}
                    {ACTIONS.find((x) => x.value === a.actionType)?.label ?? a.actionType}
                  </p>
                </div>
                <div className="flex items-center justify-between text-[11px] pt-2 hairline border-t" style={{ color: "var(--ink-muted)" }}>
                  <span className="tabular-nums">{a.runCount} runs</span>
                  <span>
                    {a.triggerType === "scheduled" && a.nextRunAt
                      ? `Next ${formatRelative(a.nextRunAt)}`
                      : a.lastRunAt
                      ? `Last ${formatRelative(a.lastRunAt)}`
                      : "Never run"}
                  </span>
                </div>
              </button>
            );
          })}
        </div>
      )}

      <Drawer
        open={!!selected || creating}
        onClose={close}
        title={selected ? selected.name : "New automation"}
        subtitle={selected ? `${selected.runCount} runs · ${selected.active ? "active" : "paused"}` : "Trigger → action"}
        width="40rem"
        headerExtra={
          selected && (
            <>
              <button className="btn-secondary text-xs" disabled={run.isPending} onClick={() => run.mutate(selected.id)}>
                <Play size={13} /> {run.isPending ? "Running…" : "Run now"}
              </button>
              <button
                className="btn-icon"
                aria-label="Delete automation"
                style={{ color: "var(--critical)" }}
                onClick={async () => {
                  const ok = await confirm("Delete this automation?", `"${selected.name}" and its run log will be removed.`);
                  if (ok) remove.mutate(selected.id);
                }}
              >
                <Trash2 size={16} />
              </button>
            </>
          )
        }
      >
        {selected && (
          <div className="mb-5">
            <Tabs
              value={drawerTab}
              onChange={setDrawerTab}
              tabs={[
                { value: "setup", label: "Setup" },
                { value: "runs", label: "Run log" },
              ]}
            />
          </div>
        )}
        {selected && drawerTab === "runs" ? (
          <RunsLog automationId={selected.id} />
        ) : (
          <div className="space-y-4">
            <AutomationEditor value={state} onChange={setState} existing={selected} privileged={privileged} />
            {selected?.triggerType === "webhook" && (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl px-4 py-3 glass-solid text-xs">
                <span style={{ color: "var(--ink-secondary)" }}>
                  Signing secret {selected.webhookSecretSet ? "is set" : "not generated yet"} — it is only shown once.
                </span>
                <button
                  className="btn-ghost text-xs"
                  disabled={rotate.isPending}
                  onClick={async () => {
                    const ok = await confirm("Generate a new secret?", "The current secret stops working immediately.", false);
                    if (ok) rotate.mutate(selected.id);
                  }}
                >
                  <RefreshCw size={13} /> {selected.webhookSecretSet ? "Rotate secret" : "Generate secret"}
                </button>
              </div>
            )}
            <div className="flex items-center justify-end gap-2 pt-4 hairline border-t">
              <button className="btn-ghost" onClick={close}>
                Cancel
              </button>
              <button className="btn-primary" disabled={!valid || save.isPending} onClick={() => save.mutate()}>
                {save.isPending ? "Saving…" : selected ? "Save changes" : "Create automation"}
              </button>
            </div>
          </div>
        )}
      </Drawer>

      <Modal
        open={!!secret}
        onClose={() => setSecret(null)}
        title="Webhook signing secret"
        width="34rem"
        footer={
          <button className="btn-primary" onClick={() => setSecret(null)}>
            I've stored it safely
          </button>
        }
      >
        {secret && (
          <div className="space-y-3 text-sm">
            <p style={{ color: "var(--ink-secondary)" }}>Copy it now — for your security it will not be shown again.</p>
            <div className="flex items-center gap-2">
              <code className="flex-1 font-mono text-[12px] break-all px-3 py-2 rounded-lg glass-solid select-all">{secret.secret}</code>
              <button
                className="btn-icon"
                aria-label="Copy secret"
                onClick={() => {
                  navigator.clipboard?.writeText(secret.secret).then(
                    () => toast.success("Copied"),
                    () => toast.error("Copy failed — select and copy manually")
                  );
                }}
              >
                <Copy size={15} />
              </button>
            </div>
            {secret.hookPath && (
              <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                Endpoint: <code className="font-mono">POST {window.location.origin}{secret.hookPath}</code>
              </p>
            )}
          </div>
        )}
      </Modal>
      {confirmNode}
    </div>
  );
}
