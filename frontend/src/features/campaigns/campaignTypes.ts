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
  phoneNumber: string;
  normalizedPhone?: string | null;
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
  count: number;
  matching: number;
  withoutPhone: number;
  truncated: boolean;
  recipients: {
    relatedType: string;
    relatedId: number;
    name: string | null;
    phone: string | null;
    normalizedPhone: string | null;
    validPhone: boolean;
    optedOut: boolean;
  }[];
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
