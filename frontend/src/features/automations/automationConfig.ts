export interface Automation {
  id: number;
  name: string;
  description: string | null;
  triggerType: TriggerType;
  triggerConfigJson: string | null;
  actionType: ActionType;
  actionConfigJson: string | null;
  apiProvider: "anthropic" | "openai";
  apiKeySet: boolean;
  apiKeyHint: string | null;
  modelId: string | null;
  active: boolean;
  runCount: number;
  lastRunAt: string | null;
  webhookSecretSet?: boolean;
  hookPath?: string | null;
  nextRunAt?: string | null;
  createdAt?: string;
  /** Only present in the create / rotate response — shown once. */
  webhookSecret?: string;
}

export interface AutomationRun {
  id: number;
  status: "running" | "completed" | "failed";
  outputText: string | null;
  errorMessage: string | null;
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  tokensUsed: number | null;
  triggerSource: string | null;
}

export interface EmailAccountOption {
  id: number;
  emailAddress: string;
  displayName: string | null;
}

export type TriggerType = "manual" | "scheduled" | "webhook" | "on_lead_created" | "on_stage_change" | "on_campaign_approved";
export type ActionType = "generate_content" | "prepare_strategy" | "analyze_data" | "send_email" | "read_email" | "custom_api";

export const TRIGGERS: { value: TriggerType; label: string; hint: string }[] = [
  { value: "manual", label: "Manual", hint: "Runs only when someone presses Run." },
  { value: "scheduled", label: "On a schedule", hint: "Cron schedule, checked every minute." },
  { value: "webhook", label: "Incoming webhook", hint: "Another system calls a signed URL." },
  { value: "on_lead_created", label: "Lead created", hint: "After a new lead is saved." },
  { value: "on_stage_change", label: "Stage changed", hint: "After a lead or deal changes stage." },
  { value: "on_campaign_approved", label: "Campaign approved", hint: "After a campaign is approved." },
];

export const ACTIONS: { value: ActionType; label: string; privileged?: boolean }[] = [
  { value: "generate_content", label: "Generate content" },
  { value: "prepare_strategy", label: "Prepare a strategy" },
  { value: "analyze_data", label: "Analyse data" },
  { value: "send_email", label: "Send an email", privileged: true },
  { value: "read_email", label: "Read & summarise inbox", privileged: true },
  { value: "custom_api", label: "Call an API", privileged: true },
];

export const CRON_PRESETS: { label: string; cron: string }[] = [
  { label: "Every weekday at 9:00", cron: "0 9 * * MON-FRI" },
  { label: "Every day at 8:00", cron: "0 8 * * *" },
  { label: "Every Monday at 10:00", cron: "0 10 * * MON" },
  { label: "Every hour", cron: "0 * * * *" },
  { label: "1st of every month at 9:00", cron: "0 9 1 * *" },
];

/** Structured view of action_config_json for the builder (unknown keys are preserved). */
export type ActionConfig = Record<string, unknown> & {
  systemPrompt?: string;
  promptTemplate?: string;
  maxTokens?: number;
  emailAccountId?: number;
  to?: string;
  subject?: string;
  folder?: string;
  limit?: number;
  analyzePrompt?: string;
  url?: string;
  method?: string;
  headersJson?: string;
  bodyTemplate?: string;
};

export function parseJsonObject(raw: string | null | undefined): Record<string, unknown> {
  if (!raw || !raw.trim()) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

/** Drops empty values; keeps numbers as numbers. */
export function serializeConfig(cfg: ActionConfig): string {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(cfg)) {
    if (v === undefined || v === null) continue;
    if (typeof v === "string" && v.trim() === "") continue;
    out[k] = v;
  }
  return JSON.stringify(out);
}

export interface TriggerConfig {
  cron?: string;
  timezone?: string;
}

export function serializeTrigger(type: TriggerType, cfg: TriggerConfig): string {
  if (type !== "scheduled") return "";
  return JSON.stringify({ cron: (cfg.cron ?? "").trim(), timezone: (cfg.timezone ?? "UTC").trim() || "UTC" });
}

/** Light client-side check (the server validates with Spring's CronExpression). */
export function looksLikeCron(expr: string | undefined): boolean {
  if (!expr) return false;
  const n = expr.trim().split(/\s+/).length;
  return n === 5 || n === 6;
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}

/** Variables each trigger provides to {{templates}}. */
export const TRIGGER_VARIABLES: Record<TriggerType, string[]> = {
  manual: ["any key you pass when running"],
  scheduled: ["schedule.firedAt", "schedule.slot"],
  webhook: ["top-level JSON fields of the request body", "webhook.receivedAt"],
  on_lead_created: ["lead.id", "lead.name", "lead.stage", "lead.source", "lead.industry", "lead.email", "lead.phone", "task"],
  on_stage_change: ["lead.name", "lead.stage", "opportunity.name", "opportunity.stage", "opportunity.value", "task"],
  on_campaign_approved: ["campaign.id", "campaign.name", "campaign.type"],
};
