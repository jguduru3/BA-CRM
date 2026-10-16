export const PROPERTY_TYPES = [
  "text",
  "number",
  "date",
  "select",
  "multi_select",
  "checkbox",
  "url",
  "email",
  "phone",
  "file",
  "relation",
  "formula",
] as const;
export type PropertyType = (typeof PROPERTY_TYPES)[number];

export const TYPE_LABEL: Record<PropertyType, string> = {
  text: "Text",
  number: "Number",
  date: "Date",
  select: "Select",
  multi_select: "Multi-select",
  checkbox: "Checkbox",
  url: "URL",
  email: "Email",
  phone: "Phone",
  file: "Files",
  relation: "Relation",
  formula: "Formula",
};

export interface DbProperty {
  id: number;
  databaseId: number;
  name: string;
  type: PropertyType;
  optionsJson: string | null;
  relationDbId: number | null;
  required: boolean;
  position: number;
}

export interface PropertyOption {
  id: string;
  label: string;
  color: string;
}

export function parseOptions(json: string | null): PropertyOption[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.map((o, i) =>
      typeof o === "string"
        ? { id: o, label: o, color: OPTION_COLORS[i % OPTION_COLORS.length] }
        : {
            id: String((o as PropertyOption).id ?? (o as PropertyOption).label),
            label: String((o as PropertyOption).label ?? (o as PropertyOption).id),
            color: (o as PropertyOption).color || OPTION_COLORS[i % OPTION_COLORS.length],
          }
    );
  } catch {
    return [];
  }
}

export const OPTION_COLORS = ["#2a78d6", "#1baf7a", "#eda100", "#e34948", "#4a3aa7", "#e87ba4", "#0d9488", "#64748b"];

/** Builds select/multi-select optionsJson from a comma-separated label list. */
export function optionsJsonFrom(labels: string): string {
  return JSON.stringify(
    labels
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean)
      .map((label, i) => ({ id: label, label, color: OPTION_COLORS[i % OPTION_COLORS.length] }))
  );
}

/** Reads a formula property's expression from optionsJson ({"expression": "..."} or a bare expression). */
export function formulaExpression(json: string | null): string {
  if (!json) return "";
  const t = json.trim();
  if (t.startsWith('{"')) {
    try {
      const parsed = JSON.parse(t) as { expression?: unknown };
      return typeof parsed.expression === "string" ? parsed.expression : "";
    } catch {
      return t;
    }
  }
  return t;
}

export interface PropertyValueRow {
  id: number | null;
  recordId: number;
  propertyId: number;
  textValue: string | null;
  numberValue: number | null;
  dateValue: string | null;
  boolValue: boolean | null;
  jsonValue: string | null;
}

export interface FormulaCell {
  value: string | number | boolean | null;
  error: string | null;
}

export interface DbRecordEnvelope {
  record: { id: number; databaseId: number; ownerId: number | null; createdBy: number | null; createdAt: string; updatedAt: string };
  values: PropertyValueRow[];
  /** propertyId → computed formula value (server-side, read-only). */
  formulas?: Record<string, FormulaCell>;
  /** linked record id → its title, for relation chips. */
  relations?: Record<string, string>;
}

function jsonArray(json: string | null): unknown[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json) as unknown;
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/** Reads a typed cell value for a property out of a record's value rows. */
export function cellValue(env: DbRecordEnvelope, prop: DbProperty): unknown {
  if (prop.type === "formula") return env.formulas?.[String(prop.id)] ?? null;
  const v = env.values.find((x) => x.propertyId === prop.id);
  if (!v) {
    if (prop.type === "checkbox") return false;
    if (prop.type === "multi_select" || prop.type === "relation" || prop.type === "file") return [];
    return null;
  }
  switch (prop.type) {
    case "number":
      return v.numberValue;
    case "checkbox":
      return v.boolValue ?? false;
    case "date":
      return v.dateValue ? v.dateValue.slice(0, 10) : v.textValue;
    case "multi_select":
      return jsonArray(v.jsonValue).map(String);
    case "relation":
    case "file":
      return jsonArray(v.jsonValue)
        .map((x) => Number(x))
        .filter((n) => Number.isFinite(n));
    default:
      return v.textValue;
  }
}

/** Builds the {propertyId, ...} payload entry the backend expects for a property type. */
export function toValuePayload(propertyId: number, type: PropertyType, raw: unknown) {
  switch (type) {
    case "number":
      return { propertyId, numberValue: raw === "" || raw == null ? null : Number(raw) };
    case "checkbox":
      return { propertyId, boolValue: !!raw };
    case "date":
      return { propertyId, dateValue: raw ? String(raw) : null };
    case "multi_select":
    case "relation":
    case "file":
      return { propertyId, jsonValue: Array.isArray(raw) ? raw : [] };
    default:
      return { propertyId, textValue: raw == null || raw === "" ? null : String(raw) };
  }
}

/** Display title of a record: its first text property, else "Untitled #id". */
export function recordTitleOf(env: DbRecordEnvelope, properties: DbProperty[]): string {
  const titleProp = properties.find((p) => p.type === "text");
  const v = titleProp ? cellValue(env, titleProp) : null;
  return typeof v === "string" && v.trim() ? v : `Untitled #${env.record.id}`;
}

/** Values that changed between two drafts (only those are sent on update). */
export function changedProps(before: Record<number, unknown>, after: Record<number, unknown>): number[] {
  return Object.keys(after)
    .map(Number)
    .filter((k) => JSON.stringify(before[k] ?? null) !== JSON.stringify(after[k] ?? null));
}
