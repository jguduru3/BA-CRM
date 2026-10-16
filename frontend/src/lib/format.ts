import { format, formatDistanceToNow, isPast, parseISO } from "date-fns";

export function formatCurrency(value: number | null | undefined): string {
  if (value == null) return "—";
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(value);
}

export function formatCompactCurrency(value: number | null | undefined): string {
  if (value == null) return "—";
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(value);
}

export function formatDate(value: string | null | undefined, pattern = "d MMM yyyy"): string {
  if (!value) return "—";
  try {
    return format(parseISO(value), pattern);
  } catch {
    return "—";
  }
}

export function formatDateTime(value: string | null | undefined): string {
  return formatDate(value, "d MMM yyyy, h:mm a");
}

export function formatRelative(value: string | null | undefined): string {
  if (!value) return "—";
  try {
    return formatDistanceToNow(parseISO(value), { addSuffix: true });
  } catch {
    return "—";
  }
}

export function isOverdue(value: string | null | undefined): boolean {
  if (!value) return false;
  try {
    return isPast(parseISO(value));
  } catch {
    return false;
  }
}

export function initialsOf(name: string | null | undefined): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/);
  const a = parts[0]?.[0] ?? "";
  const b = parts.length > 1 ? parts[parts.length - 1]?.[0] ?? "" : "";
  return (a + b).toUpperCase();
}

export function roleLabel(role: string | null | undefined): string {
  if (!role) return "—";
  return role
    .split("_")
    .map((w) => (w === "VP" ? "VP" : w[0] + w.slice(1).toLowerCase()))
    .join(" ");
}

/** Deterministic avatar color from a string, drawn from the chart series set
 * so avatar colors always harmonize with the rest of the palette. */
const AVATAR_COLORS = [
  "#2a78d6",
  "#eb6834",
  "#1baf7a",
  "#eda100",
  "#e87ba4",
  "#4a3aa7",
  "#0d699e",
  "#e34948",
];
export function colorFor(seed: string | number | null | undefined): string {
  const s = String(seed ?? "x");
  let hash = 0;
  for (let i = 0; i < s.length; i++) hash = (hash << 5) - hash + s.charCodeAt(i);
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}
