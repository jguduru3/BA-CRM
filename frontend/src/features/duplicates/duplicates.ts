// Duplicate management client: types, the duplicate-aware save helper and small
// pure helpers shared by the Lead / Contact / Company forms and the Duplicates page.
import { api, ApiError } from "@/lib/api";

export type DuplicateType = "lead" | "contact" | "company";
export type DuplicateActionValue = "ALLOW" | "WARN" | "BLOCK";

export interface DuplicateCandidate {
  id: number;
  name: string;
  matchedOn: string[];
  score: number;
}

export interface DuplicateRule {
  objectType: "LEAD" | "CONTACT" | "COMPANY";
  actionOnCreate: DuplicateActionValue;
  actionOnEdit: DuplicateActionValue;
  enabled: boolean;
  updatedAt: string | null;
}

export interface DuplicateGroup<T = Record<string, unknown>> {
  key: string;
  score: number;
  matchedOn: string[];
  records: T[];
}

export interface DuplicateGroupsPage<T = Record<string, unknown>> {
  count: number;
  data: DuplicateGroup<T>[];
  page: number;
  size: number;
  truncated: boolean;
}

/** 409 DUPLICATE_WARNING (bypassable) or DUPLICATE_BLOCKED, with the matches the caller may see. */
export class DuplicateError extends ApiError {
  candidates: DuplicateCandidate[];
  blocked: boolean;
  type: DuplicateType;
  constructor(message: string, code: string, type: DuplicateType, candidates: DuplicateCandidate[], requestId?: string) {
    super(message, 409, code, requestId);
    this.name = "DuplicateError";
    this.candidates = candidates;
    this.blocked = code === "DUPLICATE_BLOCKED";
    this.type = type;
  }
}

export function isDuplicateError(e: unknown): e is DuplicateError {
  return e instanceof DuplicateError;
}

interface DuplicateBody {
  error?: string;
  code?: string;
  requestId?: string;
  type?: DuplicateType;
  candidates?: DuplicateCandidate[];
}

/** Turns a 409 response body into the right error: DuplicateError for duplicate rules, ApiError otherwise. */
export function conflictError(data: DuplicateBody | undefined, fallbackType: DuplicateType): ApiError {
  const code = data?.code ?? "CONFLICT";
  const message = data?.error ?? "This record conflicts with existing data";
  if (code === "DUPLICATE_WARNING" || code === "DUPLICATE_BLOCKED") {
    return new DuplicateError(message, code, data?.type ?? fallbackType, data?.candidates ?? [], data?.requestId);
  }
  return new ApiError(message, 409, code, data?.requestId);
}

/**
 * POST / PATCH that understands duplicate rules. Throws {@link DuplicateError} on a
 * duplicate 409 (the axios interceptor would otherwise drop the candidate list);
 * pass `allowDuplicate` to resend after the user chose "Save anyway".
 */
export async function saveChecked<T>(
  method: "post" | "patch",
  url: string,
  body: unknown,
  type: DuplicateType,
  allowDuplicate = false
): Promise<T> {
  const res = await api.request<T | DuplicateBody>({
    method,
    url,
    data: body,
    params: allowDuplicate ? { allowDuplicate: true } : undefined,
    validateStatus: (s) => (s >= 200 && s < 300) || s === 409,
  });
  if (res.status === 409) throw conflictError(res.data as DuplicateBody, type);
  return res.data as T;
}

const PATHS: Record<DuplicateType, string> = { lead: "/leads", contact: "/contacts", company: "/companies" };

/** Where "Open existing" goes: the list page's deep link opens the record's drawer. */
export function recordHref(type: DuplicateType, id: number): string {
  return `${PATHS[type]}?open=${id}`;
}

export function apiPath(type: DuplicateType): string {
  return PATHS[type];
}

const MATCH_LABELS: Record<string, string> = {
  email: "Same email",
  phone: "Same phone",
  domain: "Same domain",
  name: "Similar name",
  company: "Same company",
};

export function matchLabel(key: string): string {
  return MATCH_LABELS[key] ?? key;
}

export function scorePercent(score: number): string {
  return `${Math.round(score * 100)}%`;
}

/** Fields shown side by side on the Duplicates page, per object type. */
export const COMPARE_FIELDS: Record<DuplicateType, { key: string; label: string }[]> = {
  lead: [
    { key: "email", label: "Email" },
    { key: "phone", label: "Phone" },
    { key: "businessName", label: "Business" },
    { key: "stage", label: "Stage" },
    { key: "source", label: "Source" },
    { key: "geography", label: "Geography" },
    { key: "value", label: "Value" },
    { key: "createdAt", label: "Created" },
  ],
  contact: [
    { key: "email", label: "Email" },
    { key: "phone", label: "Phone" },
    { key: "title", label: "Title" },
    { key: "status", label: "Status" },
    { key: "industry", label: "Industry" },
    { key: "country", label: "Country" },
    { key: "createdAt", label: "Created" },
  ],
  company: [
    { key: "website", label: "Website" },
    { key: "email", label: "Email" },
    { key: "phone", label: "Phone" },
    { key: "industry", label: "Industry" },
    { key: "location", label: "Location" },
    { key: "status", label: "Status" },
    { key: "createdAt", label: "Created" },
  ],
};

/** Field keys whose values differ across the records of a group (highlighted in the compare view). */
export function differingFields(records: Record<string, unknown>[], keys: string[]): Set<string> {
  const out = new Set<string>();
  for (const k of keys) {
    const values = new Set(records.map((r) => (r[k] === null || r[k] === undefined || r[k] === "" ? "" : String(r[k]))));
    if (values.size > 1) out.add(k);
  }
  return out;
}
