/** Audit-log types and helpers local to the admin feature. */

export interface AuditEventRow {
  id: number;
  organizationId: number;
  userId: number | null;
  userName: string | null;
  action: string;
  resource: string;
  resourceId: string | null;
  resourceName: string | null;
  beforeJson: string | null;
  afterJson: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
}

export interface AuditPage {
  count: number;
  data: AuditEventRow[];
  page: number;
  size: number;
  totalPages: number;
}

export interface AuditFacets {
  resources: string[];
  actions: string[];
}

export interface AuditFilters {
  q: string;
  resource: string;
  action: string;
  userId: string;
  from: string;
  to: string;
}

export const EMPTY_FILTERS: AuditFilters = { q: "", resource: "", action: "", userId: "", from: "", to: "" };

/** Query params for list/export — empty filters are left out entirely. */
export function filterParams(f: AuditFilters): URLSearchParams {
  const p = new URLSearchParams();
  (Object.keys(f) as (keyof AuditFilters)[]).forEach((k) => {
    const v = f[k].trim();
    if (v) p.set(k, v);
  });
  return p;
}

/** Normalizes the list payload: the paged shape, or the legacy {count,data} / bare array. */
export function toAuditPage(raw: unknown, page: number, size: number): AuditPage {
  if (Array.isArray(raw)) {
    return { count: raw.length, data: raw as AuditEventRow[], page, size, totalPages: Math.max(1, Math.ceil(raw.length / size)) };
  }
  const r = (raw ?? {}) as Partial<AuditPage>;
  const data = Array.isArray(r.data) ? r.data : [];
  const count = typeof r.count === "number" ? r.count : data.length;
  return {
    count,
    data,
    page: typeof r.page === "number" ? r.page : page,
    size: typeof r.size === "number" ? r.size : size,
    totalPages: typeof r.totalPages === "number" ? Math.max(1, r.totalPages) : Math.max(1, Math.ceil(count / size)),
  };
}

export function parseJson(raw: string | null | undefined): unknown {
  if (raw == null || raw === "") return null;
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

export function pretty(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string") return value;
  return JSON.stringify(value, null, 2);
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

export interface FieldChange {
  key: string;
  before: unknown;
  after: unknown;
  kind: "added" | "removed" | "changed";
}

/** Top-level fields whose values differ between two JSON snapshots (null when they aren't both objects). */
export function diffFields(before: unknown, after: unknown): FieldChange[] | null {
  const b = before ?? {};
  const a = after ?? {};
  if (!isPlainObject(b) || !isPlainObject(a)) return null;
  const keys = Array.from(new Set([...Object.keys(b), ...Object.keys(a)])).sort();
  const out: FieldChange[] = [];
  for (const key of keys) {
    const inB = key in b;
    const inA = key in a;
    const bv = b[key];
    const av = a[key];
    if (inB && inA && JSON.stringify(bv) === JSON.stringify(av)) continue;
    out.push({ key, before: bv, after: av, kind: !inB ? "added" : !inA ? "removed" : "changed" });
  }
  return out;
}

/** Short, single-line rendering of a field value for the diff table. */
export function inline(value: unknown): string {
  if (value === undefined) return "";
  if (value === null) return "null";
  if (typeof value === "string") return value;
  return JSON.stringify(value);
}

export type Tone = "good" | "critical" | "warning" | "brand" | "neutral";

export function actionTone(action: string): Tone {
  const a = action.toLowerCase();
  if (/(delete|purge|reject|denied|revoke|deactivate|fail|lock)/.test(a)) return "critical";
  if (/(^|_)(create|restore|approve|login|activate|accept)/.test(a)) return "good";
  if (/(update|edit|assign|convert|change|rotate|reset|move)/.test(a)) return "brand";
  if (/(export|download|import|share)/.test(a)) return "warning";
  return "neutral";
}

export function humanize(s: string): string {
  return s.replace(/[_.-]+/g, " ").trim();
}
