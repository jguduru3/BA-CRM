import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, KeyRound, ShieldCheck, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { toast } from "@/lib/toast";
import { Field, FieldGrid } from "@/components/ui/Field";
import { QueryError } from "@/components/ui/QueryError";
import { Skeleton } from "@/components/ui/Skeleton";
import { useConfirm } from "@/components/ui/ConfirmDialog";

export interface AiSettings {
  aiEnabled: boolean;
  aiProvider: "anthropic" | "openai";
  aiModel: string;
  aiKeySet: boolean;
  aiKeyHint?: string | null;
}

/** Only these two providers are supported by the backend (OrgSettingsController). */
export const AI_PROVIDERS = [
  { value: "anthropic", label: "Anthropic (Claude)", defaultModel: "claude-sonnet-5-5", keyHint: "sk-ant-…" },
  { value: "openai", label: "OpenAI (GPT)", defaultModel: "gpt-4o-mini", keyHint: "sk-…" },
] as const;

const MODEL_SUGGESTIONS: Record<string, string[]> = {
  anthropic: ["claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5-20251001"],
  openai: ["gpt-4o-mini", "gpt-4o"],
};

interface Draft {
  aiEnabled: boolean;
  aiProvider: "anthropic" | "openai";
  aiModel: string;
  aiApiKey: string;
}

const DEFAULT_DRAFT: Draft = { aiEnabled: false, aiProvider: "anthropic", aiModel: "claude-sonnet-5-5", aiApiKey: "" };

export function AiSettingsTab() {
  const qc = useQueryClient();
  const { confirm, node: confirmNode } = useConfirm();
  const settings = useQuery({
    queryKey: ["settings", "ai"],
    queryFn: async () => (await api.get<AiSettings>("/settings/ai")).data,
  });
  const [draft, setDraft] = useState<Draft>(DEFAULT_DRAFT);

  useEffect(() => {
    if (settings.data) {
      const provider = settings.data.aiProvider === "openai" ? "openai" : "anthropic";
      setDraft({ aiEnabled: settings.data.aiEnabled, aiProvider: provider, aiModel: settings.data.aiModel, aiApiKey: "" });
    }
  }, [settings.data]);

  const save = useMutation({
    mutationFn: async () => {
      const payload: Record<string, unknown> = {
        aiEnabled: draft.aiEnabled,
        aiProvider: draft.aiProvider,
        aiModel: draft.aiModel.trim(),
      };
      if (draft.aiApiKey.trim()) payload.aiApiKey = draft.aiApiKey.trim();
      return (await api.put<AiSettings>("/settings/ai", payload)).data;
    },
    onSuccess: (data) => {
      toast.success("AI settings saved");
      qc.setQueryData(["settings", "ai"], data);
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const clearKey = useMutation({
    mutationFn: async () => api.delete("/settings/ai/key"),
    onSuccess: () => {
      toast.success("API key removed — AI features are now off");
      qc.invalidateQueries({ queryKey: ["settings", "ai"] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (settings.isError) {
    return (
      <div className="glass-card">
        <QueryError error={settings.error} onRetry={() => settings.refetch()} title="Couldn't load AI settings" />
      </div>
    );
  }
  if (settings.isLoading || !settings.data) {
    return (
      <div className="glass-card p-4 space-y-4">
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }

  const current = settings.data;
  const provider = AI_PROVIDERS.find((p) => p.value === draft.aiProvider) ?? AI_PROVIDERS[0];
  const status = !current.aiKeySet
    ? { label: "No API key", cls: "badge-warning" }
    : current.aiEnabled
    ? { label: "Active", cls: "badge-good" }
    : { label: "Turned off", cls: "badge-neutral" };

  return (
    <div className="glass-card p-4 sm:p-4 space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <div
            className="flex items-center justify-center h-10 w-10 rounded-xl shrink-0"
            style={{ backgroundImage: "var(--brand-gradient)" }}
          >
            <Bot size={18} color="white" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold" style={{ color: "var(--ink-primary)" }}>
                AI Assistant
              </span>
              <span className={status.cls}>{status.label}</span>
            </div>
            <p className="text-xs mt-1 max-w-lg" style={{ color: "var(--ink-muted)" }}>
              Powers lead scoring, message drafts, summaries and next-best-action suggestions, and is the fallback key for AI
              Automations that use the same provider.
            </p>
          </div>
        </div>
        <label className="inline-flex items-center gap-2 text-sm cursor-pointer select-none" style={{ color: "var(--ink-secondary)" }}>
          <input
            type="checkbox"
            className="checkbox"
            checked={draft.aiEnabled}
            onChange={(e) => setDraft((d) => ({ ...d, aiEnabled: e.target.checked }))}
          />
          Enabled
        </label>
      </div>

      <FieldGrid>
        <Field label="Provider">
          <select
            className="select"
            value={draft.aiProvider}
            onChange={(e) => {
              const next = AI_PROVIDERS.find((p) => p.value === e.target.value) ?? AI_PROVIDERS[0];
              setDraft((d) => ({ ...d, aiProvider: next.value, aiModel: next.defaultModel }));
            }}
          >
            {AI_PROVIDERS.map((p) => (
              <option key={p.value} value={p.value}>
                {p.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Model" hint={`Default: ${provider.defaultModel}`}>
          <input
            className="input"
            list="ai-model-suggestions"
            value={draft.aiModel}
            onChange={(e) => setDraft((d) => ({ ...d, aiModel: e.target.value }))}
          />
          <datalist id="ai-model-suggestions">
            {MODEL_SUGGESTIONS[draft.aiProvider].map((m) => (
              <option key={m} value={m} />
            ))}
          </datalist>
        </Field>
      </FieldGrid>

      <Field
        label="API key"
        hint={
          current.aiKeySet
            ? `A key ending in ${current.aiKeyHint ?? "••••"} is saved and encrypted. Enter a new key only to replace it.`
            : "No key saved yet. Keys are encrypted at rest and never shown again."
        }
      >
        <div className="relative">
          <KeyRound size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: "var(--ink-muted)" }} />
          <input
            className="input pl-8"
            type="password"
            autoComplete="off"
            placeholder={current.aiKeySet ? `•••• •••• ${current.aiKeyHint ?? ""}`.trim() : provider.keyHint}
            value={draft.aiApiKey}
            onChange={(e) => setDraft((d) => ({ ...d, aiApiKey: e.target.value }))}
          />
        </div>
      </Field>

      <div className="flex flex-col-reverse sm:flex-row sm:items-center sm:justify-between gap-3 pt-4 hairline border-t">
        {current.aiKeySet ? (
          <button
            className="btn-ghost self-start"
            style={{ color: "var(--critical)" }}
            disabled={clearKey.isPending}
            onClick={async () => {
              const ok = await confirm("Remove the saved API key?", "AI features will be turned off until a new key is saved.");
              if (ok) clearKey.mutate();
            }}
          >
            <Trash2 size={14} /> Remove key
          </button>
        ) : (
          <span className="inline-flex items-center gap-1.5 text-xs" style={{ color: "var(--ink-muted)" }}>
            <ShieldCheck size={13} /> Only Super Admins can change these settings.
          </span>
        )}
        <button
          className="btn-primary"
          disabled={save.isPending || !draft.aiModel.trim() || (draft.aiEnabled && !current.aiKeySet && !draft.aiApiKey.trim())}
          onClick={() => save.mutate()}
        >
          {save.isPending ? "Saving…" : "Save AI settings"}
        </button>
      </div>
      {confirmNode}
    </div>
  );
}
