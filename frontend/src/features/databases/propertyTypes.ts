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
    return JSON.parse(json);
  } catch {
    return [];
  }
}

export interface PropertyValueRow {
  id: number;
  recordId: number;
  propertyId: number;
  textValue: string | null;
  numberValue: number | null;
  dateValue: string | null;
  boolValue: boolean | null;
  jsonValue: string | null;
}

export interface DbRecordEnvelope {
  record: { id: number; databaseId: number; ownerId: number | null; createdBy: number | null; createdAt: string; updatedAt: string };
  values: PropertyValueRow[];
}

/** Read a typed cell value for a property out of a record's value rows. */
export function cellValue(env: DbRecordEnvelope, prop: DbProperty): unknown {
  const v = env.values.find((x) => x.propertyId === prop.id);
  if (!v) return null;
  switch (prop.type) {
    case "number":
      return v.numberValue;
    case "checkbox":
      return v.boolValue ?? false;
    case "multi_select":
      try {
        return v.jsonValue ? (JSON.parse(v.jsonValue) as string[]) : [];
      } catch {
        return [];
      }
    default:
      return v.textValue;
  }
}

/** Build the {propertyId, ...} payload entries the backend's setPropertyValues expects. */
export function toValuePayload(propertyId: number, type: PropertyType, raw: unknown) {
  switch (type) {
    case "number":
      return { propertyId, numberValue: raw === "" || raw == null ? null : Number(raw) };
    case "checkbox":
      return { propertyId, boolValue: !!raw };
    case "multi_select":
      return { propertyId, jsonValue: JSON.stringify(raw ?? []) };
    default:
      return { propertyId, textValue: raw == null ? "" : String(raw) };
  }
}
