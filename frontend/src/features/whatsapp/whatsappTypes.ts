export interface WaAccount {
  id: number;
  displayPhoneNumber: string | null;
  active: boolean;
  // Only for roles that may manage senders:
  phoneNumberId?: string;
  businessAccountId?: string | null;
  hasAccessToken?: boolean;
  accessTokenHint?: string | null;
  webhookVerifyTokenSet?: boolean;
  createdAt?: string;
}

export type WaStatus = "queued" | "sending" | "sent" | "delivered" | "read" | "failed" | "received";

export interface WaMessage {
  id: number;
  accountId: number | null;
  direction: "inbound" | "outbound";
  body: string | null;
  bodyHidden?: boolean;
  type: string;
  templateName?: string | null;
  status: WaStatus | string;
  fromNumber: string | null;
  toNumber: string | null;
  normalizedPhone?: string | null;
  errorMessage?: string | null;
  relatedType?: string | null;
  relatedId?: number | null;
  sentBy?: number | null;
  campaignId?: number | null;
  createdAt: string;
  deliveredAt?: string | null;
  readAt?: string | null;
}

export interface WaThread {
  phone: string;
  lastMessageAt: string;
  messageCount: number;
  lastMessage: WaMessage | null;
  relatedType: string | null;
  relatedId: number | null;
  optedOut: boolean;
}

export interface ConsentEvent {
  id: number;
  status: "opted_in" | "opted_out";
  source: string;
  reason: string | null;
  actorUserId: number | null;
  createdAt: string;
}

export interface ConsentView {
  phone: string;
  status: "opted_in" | "opted_out" | "unknown";
  optedOut: boolean;
  source: string | null;
  reason: string | null;
  updatedAt: string | null;
  history: ConsentEvent[];
}

/** Same rule as the backend's PhoneNumbers.tryNormalize: E.164 with a leading '+', or null. */
export function normalizePhone(raw: string): string | null {
  let p = raw.trim().replace(/[\s\-().]/g, "");
  if (p.startsWith("00")) p = "+" + p.slice(2);
  if (!p.startsWith("+")) p = "+" + p;
  return /^\+[1-9]\d{7,14}$/.test(p) ? p : null;
}
