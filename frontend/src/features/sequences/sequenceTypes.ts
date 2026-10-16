// Feature-local types and helpers for multi-channel sequences (/api/sequences).

export type Channel = "EMAIL" | "WHATSAPP" | "TASK";
export type EnrollmentStatus = "ACTIVE" | "PAUSED" | "RESPONDED" | "COMPLETED" | "STOPPED" | "FAILED";

export interface SequenceStep {
  id?: number;
  stepIndex?: number;
  channel: Channel;
  delayDays: number;
  delayHours: number;
  subject?: string | null;
  body?: string | null;
  waTemplateName?: string | null;
  waTemplateLanguage?: string | null;
  waTemplateParams?: string[];
}

export interface Sequence {
  id: number;
  name: string;
  description?: string | null;
  active: boolean;
  emailAccountId?: number | null;
  whatsappAccountId?: number | null;
  createdAt?: string;
  updatedAt?: string;
  version?: number;
  steps: SequenceStep[];
  enrollmentCounts: Partial<Record<EnrollmentStatus, number>>;
  activeEnrollments: number;
}

export interface Enrollment {
  id: number;
  sequenceId: number;
  sequenceName?: string | null;
  relatedType: "Lead" | "Contact";
  relatedId: number;
  recordName?: string | null;
  ownerUserId?: number | null;
  enrolledBy?: number | null;
  status: EnrollmentStatus;
  currentStep: number;
  stepCount: number;
  nextRunAt?: string | null;
  skippedChannels: Channel[];
  stopReason?: string | null;
  respondedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  updatedAt?: string;
}

export interface EnrollmentEvent {
  id: number;
  type: string;
  stepIndex?: number | null;
  channel?: Channel | null;
  detail?: string | null;
  actorId?: number | null;
  createdAt: string;
}

export interface StepExecution {
  stepIndex: number;
  channel: Channel;
  status: "PENDING" | "SENT" | "SKIPPED" | "FAILED";
  detail?: string | null;
  taskId?: number | null;
  createdAt: string;
  completedAt?: string | null;
}

export interface EnrollmentDetail extends Enrollment {
  events: EnrollmentEvent[];
  executions: StepExecution[];
}

export const CHANNEL_LABEL: Record<Channel, string> = {
  EMAIL: "Email",
  WHATSAPP: "WhatsApp",
  TASK: "Task",
};

export const STATUS_LABEL: Record<EnrollmentStatus, string> = {
  ACTIVE: "Active",
  PAUSED: "Paused",
  RESPONDED: "Responded",
  COMPLETED: "Completed",
  STOPPED: "Stopped",
  FAILED: "Failed",
};

export const STATUS_TONE: Record<EnrollmentStatus, string> = {
  ACTIVE: "badge badge-brand",
  PAUSED: "badge badge-warning",
  RESPONDED: "badge badge-good",
  COMPLETED: "badge badge-neutral",
  STOPPED: "badge badge-neutral",
  FAILED: "badge badge-critical",
};

export const EVENT_LABEL: Record<string, string> = {
  ENROLLED: "Enrolled",
  STEP_SENT: "Step sent",
  STEP_SKIPPED: "Step skipped",
  STEP_FAILED: "Step failed",
  CHANNEL_SKIPPED: "Channel skipped",
  RESPONDED: "Responded",
  PAUSED: "Paused",
  RESUMED: "Resumed",
  STOPPED: "Stopped",
  COMPLETED: "Completed",
  FAILED: "Failed",
};

export function isLive(status: EnrollmentStatus): boolean {
  return status === "ACTIVE" || status === "PAUSED";
}

export function blankStep(channel: Channel = "EMAIL"): SequenceStep {
  return {
    channel,
    delayDays: 0,
    delayHours: 0,
    subject: "",
    body: "",
    waTemplateName: "",
    waTemplateLanguage: "en_US",
    waTemplateParams: [],
  };
}

/** "Immediately", "After 2 days", "After 1 day 4 h" — delay is from the previous step. */
export function describeDelay(days: number, hours: number): string {
  if (!days && !hours) return "Immediately";
  const parts: string[] = [];
  if (days) parts.push(`${days} day${days === 1 ? "" : "s"}`);
  if (hours) parts.push(`${hours} h`);
  return `After ${parts.join(" ")}`;
}

/** Cumulative offset from enrollment, in hours, for each step. */
export function cumulativeHours(steps: Pick<SequenceStep, "delayDays" | "delayHours">[]): number[] {
  let total = 0;
  return steps.map((s) => {
    total += (s.delayDays || 0) * 24 + (s.delayHours || 0);
    return total;
  });
}

export function dayLabel(hours: number): string {
  const day = Math.floor(hours / 24) + 1;
  const h = hours % 24;
  return h ? `Day ${day} · +${h}h` : `Day ${day}`;
}

const TEMPLATE_NAME = /^[A-Za-z0-9_]{1,100}$/;
const LANGUAGE = /^[a-z]{2,3}(_[A-Z]{2})?$/;

/** Mirrors the server's step validation so the builder can explain problems before saving. */
export function validateStep(s: SequenceStep): string | null {
  if (!Number.isInteger(s.delayDays) || s.delayDays < 0 || s.delayDays > 365) return "Delay days must be 0–365";
  if (!Number.isInteger(s.delayHours) || s.delayHours < 0 || s.delayHours > 23 * 365) return "Delay hours must be 0 or more";
  if (s.channel === "EMAIL") {
    if (!s.subject?.trim()) return "Email steps need a subject";
    if (!s.body?.trim()) return "Email steps need a body";
  }
  if (s.channel === "TASK" && !s.subject?.trim()) return "Task steps need a title";
  if (s.channel === "WHATSAPP") {
    if (!s.waTemplateName || !TEMPLATE_NAME.test(s.waTemplateName)) return "Use the approved template's name (letters, digits, _)";
    if (s.waTemplateLanguage && !LANGUAGE.test(s.waTemplateLanguage)) return "Language looks like en_US";
    if ((s.waTemplateParams ?? []).length > 10) return "At most 10 template parameters";
  }
  if ((s.subject?.length ?? 0) > 300) return "Subject is too long (300 max)";
  return null;
}

/** Request body for POST/PUT /sequences. */
export function toPayload(draft: { name: string; description?: string | null; active: boolean; steps: SequenceStep[] }) {
  return {
    name: draft.name.trim(),
    description: draft.description?.trim() || null,
    active: draft.active,
    steps: draft.steps.map((s) => {
      const base = { channel: s.channel, delayDays: s.delayDays || 0, delayHours: s.delayHours || 0 };
      if (s.channel === "WHATSAPP") {
        return {
          ...base,
          waTemplateName: s.waTemplateName?.trim(),
          waTemplateLanguage: s.waTemplateLanguage?.trim() || "en_US",
          waTemplateParams: (s.waTemplateParams ?? []).map((p) => p.trim()).filter((p) => p.length > 0),
        };
      }
      return { ...base, subject: s.subject?.trim() || null, body: s.body?.trim() || null };
    }),
  };
}

export const TEMPLATE_VARIABLES = [
  "{{record.name}}",
  "{{record.firstName}}",
  "{{lead.name}}",
  "{{lead.businessName}}",
  "{{contact.name}}",
  "{{contact.title}}",
  "{{owner.name}}",
  "{{owner.email}}",
];
