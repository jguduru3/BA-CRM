import type { Role } from "@/lib/types";

export type CampaignStatus =
  | "draft"
  | "submitted"
  | "approved"
  | "rejected"
  | "scheduled"
  | "sending"
  | "paused"
  | "completed"
  | "cancelled";

export interface CampaignRow {
  id: number;
  name: string;
  type: "whatsapp" | "email" | "sms";
  status: CampaignStatus;
  waAccountId?: number | null;
  templateName?: string | null;
  templateLanguage?: string | null;
  templateParams?: string | null;
  messageBody?: string | null;
  emailAccountId?: number | null;
  emailSubject?: string | null;
  emailBody?: string | null;
  scheduledAt?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  totalContacts: number;
  sentCount: number;
  deliveredCount: number;
  readCount: number;
  failedCount: number;
  createdBy: number;
  ownerId?: number | null;
  submittedAt?: string | null;
  approvedBy?: number | null;
  approvedAt?: string | null;
  rejectionReason?: string | null;
  audienceCriteriaJson?: string | null;
  version: number;
  createdAt: string;
  updatedAt?: string;
}

export interface CampaignProgress {
  id: number;
  status: CampaignStatus;
  version: number;
  total: number;
  processed: number;
  percent: number;
  counts: Record<string, number>;
  sentCount: number;
  deliveredCount: number;
  readCount: number;
  failedCount: number;
  startedAt?: string | null;
  completedAt?: string | null;
}

export interface CampaignRecipient {
  id: number;
  phoneNumber: string | null;
  normalizedPhone?: string | null;
  email?: string | null;
  emailNormalized?: string | null;
  name?: string | null;
  status: string;
  relatedType?: string | null;
  relatedId?: number | null;
  sentAt?: string | null;
  deliveredAt?: string | null;
  readAt?: string | null;
  failedReason?: string | null;
}

export interface CampaignDetail {
  campaign: CampaignRow;
  contacts: CampaignRecipient[];
  progress: CampaignProgress;
}

export interface AddRecipientsResult {
  added: number;
  invalid: number;
  duplicates: number;
  optedOut?: number;
  total: number;
}

export interface AudiencePreview {
  /** Which lead field the campaign's channel needs: "phone" (WhatsApp / SMS) or "email". */
  channelField?: "phone" | "email";
  count: number;
  matching: number;
  withoutPhone: number;
  withoutEmail?: number;
  truncated: boolean;
  recipients: {
    relatedType: string;
    relatedId: number;
    name: string | null;
    phone: string | null;
    normalizedPhone: string | null;
    validPhone: boolean;
    email?: string | null;
    validEmail?: boolean;
    optedOut: boolean;
  }[];
}

export type CampaignType = CampaignRow["type"];

export const CHANNEL_LABEL: Record<CampaignType, string> = {
  whatsapp: "WhatsApp",
  email: "Email",
  sms: "SMS",
};

/** GET /campaigns/channels — which channels can send for this organization. */
export interface ChannelStatus {
  configured: boolean;
  detail: string;
  from?: string | null;
}
export interface CampaignChannels {
  whatsapp: ChannelStatus;
  email: ChannelStatus;
  sms: ChannelStatus;
  emailPerMinute?: number;
}

/** Template variables offered for email / SMS bodies (resolved per recipient on the server). */
export const MESSAGE_VARIABLES = [
  "{{contact.name}}",
  "{{contact.firstName}}",
  "{{contact.email}}",
  "{{owner.name}}",
  "{{campaign.name}}",
  "{{lead.stage}}",
] as const;

/** GSM 03.38 basic + extension characters; anything else forces UCS-2 (70 chars per SMS). */
const GSM_BASIC =
  "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
const GSM_EXT = "^{}\\[~]|€\f";

export interface SmsSegments {
  chars: number;
  segments: number;
  unicode: boolean;
  /** Characters available per segment for this encoding (single / multi-part). */
  single: number;
  multi: number;
}

/** SMS length: 160 GSM-7 chars in one message, 153 per part when longer (306 for two); 70 / 67 for Unicode. */
export function smsSegments(text: string): SmsSegments {
  let units = 0;
  let unicode = false;
  for (const ch of text) {
    if (GSM_BASIC.includes(ch)) units += 1;
    else if (GSM_EXT.includes(ch)) units += 2;
    else {
      unicode = true;
      break;
    }
  }
  if (unicode) {
    const len = [...text].reduce((n, ch) => n + (ch.codePointAt(0)! > 0xffff ? 2 : 1), 0);
    return { chars: len, segments: len === 0 ? 0 : len <= 70 ? 1 : Math.ceil(len / 67), unicode, single: 70, multi: 67 };
  }
  return { chars: units, segments: units === 0 ? 0 : units <= 160 ? 1 : Math.ceil(units / 153), unicode, single: 160, multi: 153 };
}

/** Why a campaign can't be submitted yet (mirrors the server's checks), or null. */
export function submitBlocker(
  c: Pick<CampaignRow, "type" | "templateName" | "emailSubject" | "emailBody" | "messageBody">
): string | null {
  if (c.type === "whatsapp" && !c.templateName?.trim()) return "Add a template name first";
  if (c.type === "email" && (!c.emailSubject?.trim() || !c.emailBody?.trim())) return "Add a subject and a message first";
  if (c.type === "sms" && !c.messageBody?.trim()) return "Write the SMS message first";
  return null;
}

export interface AudienceFilter {
  field: "stage" | "priority" | "industry" | "geography" | "source" | "value";
  op: "eq" | "neq" | "in" | "gte" | "lte" | "gt" | "lt";
  value?: string | number;
  values?: string[];
}

export type CampaignAction = "submit" | "approve" | "reject" | "launch" | "pause" | "resume" | "cancel" | "edit" | "delete";

export const STATUS_LABEL: Record<CampaignStatus, string> = {
  draft: "Draft",
  submitted: "Awaiting approval",
  approved: "Approved",
  rejected: "Rejected",
  scheduled: "Scheduled",
  sending: "Sending",
  paused: "Paused",
  completed: "Completed",
  cancelled: "Cancelled",
};

export const STATUS_TONE: Record<CampaignStatus, string> = {
  draft: "badge-neutral",
  submitted: "badge-warning",
  approved: "badge-brand",
  rejected: "badge-critical",
  scheduled: "badge-brand",
  sending: "badge-brand",
  paused: "badge-warning",
  completed: "badge-good",
  cancelled: "badge-neutral",
};

/** Legacy names some older rows / clients may still carry. */
export function normalizeStatus(raw: string): CampaignStatus {
  if (raw === "running") return "sending";
  if (raw === "pending_approval") return "submitted";
  return raw as CampaignStatus;
}

export interface Actor {
  id: number;
  role: Role;
}

/**
 * Which lifecycle actions the UI offers — mirrors CampaignController exactly
 * (the server re-checks everything):
 *   - only SUPER_ADMIN / VP_SALES manage campaigns at all;
 *   - approve / reject: SUPER_ADMIN or VP_SALES, never on your own campaign unless SUPER_ADMIN;
 *   - launch from draft (skipping approval): SUPER_ADMIN only; launch from approved: any manager of campaigns.
 */
export function allowedActions(c: Pick<CampaignRow, "status" | "createdBy">, actor: Actor | null | undefined): CampaignAction[] {
  if (!actor) return [];
  const manages = actor.role === "SUPER_ADMIN" || actor.role === "VP_SALES";
  if (!manages) return [];
  const isSuper = actor.role === "SUPER_ADMIN";
  const own = c.createdBy === actor.id;
  const canReview = isSuper || !own;
  const status = normalizeStatus(c.status);
  const out: CampaignAction[] = [];
  switch (status) {
    case "draft":
      out.push("edit", "submit");
      if (isSuper) out.push("launch");
      out.push("cancel", "delete");
      break;
    case "rejected":
      out.push("edit", "submit", "cancel", "delete");
      break;
    case "submitted":
      if (canReview) out.push("approve", "reject");
      out.push("cancel");
      break;
    case "approved":
      out.push("launch", "cancel");
      break;
    case "scheduled":
      out.push("cancel");
      break;
    case "sending":
      out.push("pause", "cancel");
      break;
    case "paused":
      out.push("resume", "cancel");
      break;
    default:
      break;
  }
  return out;
}

export function canEditRecipients(status: CampaignStatus): boolean {
  return status === "draft" || status === "rejected";
}
