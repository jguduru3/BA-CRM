import { useQuery } from "@tanstack/react-query";
import { CalendarClock, KeyRound, Webhook, Zap } from "lucide-react";
import { api } from "@/lib/api";
import { Field, FieldGrid, SectionTitle } from "@/components/ui/Field";
import { AI_PROVIDERS } from "@/features/settings/AiSettingsTab";
import {
  ACTIONS,
  type ActionConfig,
  type ActionType,
  type Automation,
  CRON_PRESETS,
  type EmailAccountOption,
  looksLikeCron,
  TRIGGER_VARIABLES,
  type TriggerConfig,
  TRIGGERS,
  type TriggerType,
} from "./automationConfig";

export interface EditorState {
  name: string;
  description: string;
  triggerType: TriggerType;
  trigger: TriggerConfig;
  actionType: ActionType;
  config: ActionConfig;
  apiProvider: "anthropic" | "openai";
  modelId: string;
  apiKey: string;
  clearApiKey: boolean;
  active: boolean;
}

const AI_ACTIONS: ActionType[] = ["generate_content", "prepare_strategy", "analyze_data", "send_email"];

export function AutomationEditor({
  value,
  onChange,
  existing,
  privileged,
}: {
  value: EditorState;
  onChange: (next: EditorState) => void;
  existing: Automation | null;
  privileged: boolean;
}) {
  const set = (patch: Partial<EditorState>) => onChange({ ...value, ...patch });
  const setCfg = (patch: Partial<ActionConfig>) => onChange({ ...value, config: { ...value.config, ...patch } });
  const needsEmail = value.actionType === "send_email" || value.actionType === "read_email";
  const emailAccounts = useQuery({
    queryKey: ["ai-automations", "email-accounts"],
    queryFn: async () => (await api.get<EmailAccountOption[]>("/ai-automations/email-accounts")).data,
    enabled: privileged && needsEmail,
    retry: 0,
  });
  const provider = AI_PROVIDERS.find((p) => p.value === value.apiProvider) ?? AI_PROVIDERS[0];
  const usesAi = AI_ACTIONS.includes(value.actionType) || (value.actionType === "read_email" && !!value.config.analyzePrompt);
  const str = (k: keyof ActionConfig) => (typeof value.config[k] === "string" ? (value.config[k] as string) : "");
  const num = (k: keyof ActionConfig) => (typeof value.config[k] === "number" ? String(value.config[k]) : "");

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <Field label="Name" required>
          <input className="input" maxLength={200} value={value.name} onChange={(e) => set({ name: e.target.value })} />
        </Field>
        <Field label="Description">
          <textarea className="textarea" rows={2} value={value.description} onChange={(e) => set({ description: e.target.value })} />
        </Field>
      </div>

      {/* WHEN */}
      <div className="rounded-xl p-4 space-y-3" style={{ backgroundColor: "rgb(var(--ring-brand) / 0.05)" }}>
        <div className="flex items-center gap-2">
          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full text-white" style={{ backgroundImage: "var(--brand-gradient)" }}>
            When
          </span>
          <span className="text-xs" style={{ color: "var(--ink-muted)" }}>
            {TRIGGERS.find((t) => t.value === value.triggerType)?.hint}
          </span>
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
          {TRIGGERS.map((t) => {
            const on = t.value === value.triggerType;
            return (
              <button
                key={t.value}
                type="button"
                onClick={() => set({ triggerType: t.value })}
                className="rounded-lg px-3 py-2 text-xs font-medium text-left transition-all hairline border"
                style={{
                  backgroundColor: on ? "rgb(var(--ring-brand) / 0.12)" : "transparent",
                  borderColor: on ? "var(--brand-500)" : undefined,
                  color: on ? "var(--brand-600)" : "var(--ink-secondary)",
                }}
              >
                {t.label}
              </button>
            );
          })}
        </div>

        {value.triggerType === "scheduled" && (
          <div className="space-y-3 pt-1">
            <div className="flex flex-wrap gap-1.5">
              {CRON_PRESETS.map((p) => (
                <button
                  key={p.cron}
                  type="button"
                  className="text-[11px] px-2 py-1 rounded-full hairline border"
                  style={{ color: value.trigger.cron === p.cron ? "var(--brand-600)" : "var(--ink-secondary)" }}
                  onClick={() => set({ trigger: { ...value.trigger, cron: p.cron } })}
                >
                  {p.label}
                </button>
              ))}
            </div>
            <FieldGrid>
              <Field label="Cron expression" required hint="minute hour day month weekday — e.g. 0 9 * * MON-FRI">
                <div className="relative">
                  <CalendarClock size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: "var(--ink-muted)" }} />
                  <input
                    className="input pl-8 font-mono text-[13px]"
                    value={value.trigger.cron ?? ""}
                    onChange={(e) => set({ trigger: { ...value.trigger, cron: e.target.value } })}
                    style={value.trigger.cron && !looksLikeCron(value.trigger.cron) ? { borderColor: "var(--critical)" } : undefined}
                  />
                </div>
              </Field>
              <Field label="Time zone" hint="IANA id, e.g. Asia/Kolkata">
                <input className="input" placeholder="UTC" value={value.trigger.timezone ?? ""} onChange={(e) => set({ trigger: { ...value.trigger, timezone: e.target.value } })} />
              </Field>
            </FieldGrid>
            {existing?.nextRunAt && existing.triggerType === "scheduled" && (
              <p className="text-xs" style={{ color: "var(--ink-muted)" }}>
                Next run: {new Date(existing.nextRunAt).toLocaleString()}
              </p>
            )}
          </div>
        )}

        {value.triggerType === "webhook" && (
          <div className="text-xs space-y-1.5 pt-1" style={{ color: "var(--ink-secondary)" }}>
            <p className="inline-flex items-center gap-1.5">
              <Webhook size={13} /> {existing?.hookPath ? (
                <code className="font-mono px-1.5 py-0.5 rounded glass-solid break-all">POST {existing.hookPath}</code>
              ) : (
                "A signed URL and secret are generated when you save."
              )}
            </p>
            <p style={{ color: "var(--ink-muted)" }}>
              Sign each call: <code className="font-mono">X-Timestamp</code> (unix seconds) and{" "}
              <code className="font-mono">X-Signature: sha256=hex(HMAC(secret, timestamp + "." + body))</code>. Calls older than 5 minutes are rejected.
            </p>
          </div>
        )}
        <p className="text-[11px]" style={{ color: "var(--ink-muted)" }}>
          Variables: {TRIGGER_VARIABLES[value.triggerType].map((v) => `{{${v}}}`).join(", ")}
        </p>
      </div>

      {/* THEN */}
      <div className="rounded-xl p-4 space-y-4 glass-solid">
        <div className="flex items-center gap-2">
          <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full" style={{ backgroundColor: "rgb(var(--panel-border) / 0.12)", color: "var(--ink-secondary)" }}>
            Then
          </span>
          <Zap size={13} style={{ color: "var(--brand-600)" }} />
        </div>
        <Field label="Action">
          <select className="select" value={value.actionType} onChange={(e) => set({ actionType: e.target.value as ActionType })}>
            {ACTIONS.map((a) => (
              <option key={a.value} value={a.value} disabled={a.privileged && !privileged}>
                {a.label}
                {a.privileged && !privileged ? " (VP / Super Admin)" : ""}
              </option>
            ))}
          </select>
        </Field>

        {needsEmail && (
          <Field label="Email account" required hint={emailAccounts.isError ? "Couldn't load email accounts" : undefined}>
            <select className="select" value={num("emailAccountId")} onChange={(e) => setCfg({ emailAccountId: e.target.value ? Number(e.target.value) : undefined })}>
              <option value="">Choose an account…</option>
              {emailAccounts.data?.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.displayName ? `${a.displayName} <${a.emailAddress}>` : a.emailAddress}
                </option>
              ))}
            </select>
          </Field>
        )}

        {value.actionType === "send_email" && (
          <FieldGrid>
            <Field label="To" required hint="One address, e.g. {{lead.email}}">
              <input className="input" value={str("to")} onChange={(e) => setCfg({ to: e.target.value })} />
            </Field>
            <Field label="Subject">
              <input className="input" value={str("subject")} onChange={(e) => setCfg({ subject: e.target.value })} />
            </Field>
          </FieldGrid>
        )}

        {value.actionType === "read_email" && (
          <FieldGrid>
            <Field label="Folder">
              <input className="input" placeholder="INBOX" value={str("folder")} onChange={(e) => setCfg({ folder: e.target.value })} />
            </Field>
            <Field label="Messages to read" hint="1–50">
              <input className="input" type="number" min={1} max={50} value={num("limit")} onChange={(e) => setCfg({ limit: e.target.value ? Number(e.target.value) : undefined })} />
            </Field>
          </FieldGrid>
        )}

        {value.actionType === "custom_api" && (
          <>
            <FieldGrid>
              <Field label="Method">
                <select className="select" value={str("method") || "POST"} onChange={(e) => setCfg({ method: e.target.value })}>
                  {["GET", "POST", "PUT", "PATCH", "DELETE"].map((m) => (
                    <option key={m}>{m}</option>
                  ))}
                </select>
              </Field>
              <Field label="URL" required hint="Host must be literal; {{vars}} only in the path/query">
                <input className="input font-mono text-[12px]" placeholder="https://api.example.com/items/{{lead.id}}" value={str("url")} onChange={(e) => setCfg({ url: e.target.value })} />
              </Field>
            </FieldGrid>
            <Field label="Headers (JSON)">
              <textarea className="textarea font-mono text-[12px]" rows={2} placeholder='{"Authorization":"Bearer …"}' value={str("headersJson")} onChange={(e) => setCfg({ headersJson: e.target.value })} />
            </Field>
            <Field label="Body template">
              <textarea className="textarea font-mono text-[12px]" rows={3} placeholder='{"name":"{{lead.name}}"}' value={str("bodyTemplate")} onChange={(e) => setCfg({ bodyTemplate: e.target.value })} />
            </Field>
          </>
        )}

        {(AI_ACTIONS.includes(value.actionType) || value.actionType === "read_email") && (
          <>
            {value.actionType !== "read_email" && (
              <Field label="System prompt">
                <textarea className="textarea" rows={2} placeholder="You are a professional CRM AI assistant." value={str("systemPrompt")} onChange={(e) => setCfg({ systemPrompt: e.target.value })} />
              </Field>
            )}
            <Field
              label={value.actionType === "read_email" ? "Analysis prompt (optional)" : value.actionType === "send_email" ? "Email body prompt" : "Prompt"}
              hint="Use {{variables}} from the trigger"
            >
              <textarea
                className="textarea"
                rows={4}
                placeholder={value.actionType === "read_email" ? "Summarise action items from these emails" : "Draft a follow-up for {{lead.name}} who is now in {{lead.stage}}."}
                value={value.actionType === "read_email" ? str("analyzePrompt") : str("promptTemplate")}
                onChange={(e) => (value.actionType === "read_email" ? setCfg({ analyzePrompt: e.target.value }) : setCfg({ promptTemplate: e.target.value }))}
              />
            </Field>
            {value.actionType !== "read_email" && (
              <Field label="Max tokens" hint="1–8000">
                <input className="input" type="number" min={1} max={8000} value={num("maxTokens")} placeholder="2000" onChange={(e) => setCfg({ maxTokens: e.target.value ? Number(e.target.value) : undefined })} />
              </Field>
            )}
          </>
        )}
      </div>

      {usesAi && (
        <div className="space-y-4">
          <SectionTitle>AI model</SectionTitle>
          <FieldGrid>
            <Field label="Provider">
              <select
                className="select"
                value={value.apiProvider}
                onChange={(e) => {
                  const p = AI_PROVIDERS.find((x) => x.value === e.target.value) ?? AI_PROVIDERS[0];
                  set({ apiProvider: p.value, modelId: p.defaultModel });
                }}
              >
                {AI_PROVIDERS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Model">
              <input className="input" value={value.modelId} placeholder={provider.defaultModel} onChange={(e) => set({ modelId: e.target.value })} />
            </Field>
          </FieldGrid>
          <Field
            label="API key (optional)"
            hint={
              existing?.apiKeySet && !value.clearApiKey
                ? `This automation has its own key ending in ${existing.apiKeyHint ?? "••••"}. Leave blank to keep it.`
                : "Leave blank to use the organisation key from Settings → AI Assistant (same provider)."
            }
          >
            <div className="relative">
              <KeyRound size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: "var(--ink-muted)" }} />
              <input
                className="input pl-8"
                type="password"
                autoComplete="off"
                disabled={value.clearApiKey}
                placeholder={existing?.apiKeySet ? `•••• •••• ${existing.apiKeyHint ?? ""}`.trim() : provider.keyHint}
                value={value.apiKey}
                onChange={(e) => set({ apiKey: e.target.value })}
              />
            </div>
          </Field>
          {existing?.apiKeySet && (
            <label className="inline-flex items-center gap-2 text-xs" style={{ color: "var(--ink-secondary)" }}>
              <input type="checkbox" className="checkbox" checked={value.clearApiKey} onChange={(e) => set({ clearApiKey: e.target.checked, apiKey: "" })} />
              Remove this automation's own key (fall back to the organisation key)
            </label>
          )}
        </div>
      )}

      <label className="inline-flex items-center gap-2 text-sm" style={{ color: "var(--ink-secondary)" }}>
        <input type="checkbox" className="checkbox" checked={value.active} onChange={(e) => set({ active: e.target.checked })} />
        Active — triggers fire only while the automation is active
      </label>
    </div>
  );
}
