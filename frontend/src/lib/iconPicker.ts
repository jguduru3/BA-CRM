import {
  Archive,
  Box,
  Briefcase,
  Building2,
  Compass,
  Database,
  FileText,
  Flag,
  Folder,
  Layers,
  LayoutGrid,
  ListChecks,
  Map as MapIcon,
  Package,
  Puzzle,
  Rocket,
  Target,
  Users,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";

/** Curated vector glyph set for user-pickable "icons" on custom entities
 * (databases, projects, lists) — no emoji, per design system rules. */
export const ICON_PICKS: { key: string; icon: LucideIcon }[] = [
  { key: "database", icon: Database },
  { key: "folder", icon: Folder },
  { key: "layout-grid", icon: LayoutGrid },
  { key: "list-checks", icon: ListChecks },
  { key: "target", icon: Target },
  { key: "flag", icon: Flag },
  { key: "briefcase", icon: Briefcase },
  { key: "building", icon: Building2 },
  { key: "users", icon: Users },
  { key: "box", icon: Box },
  { key: "package", icon: Package },
  { key: "layers", icon: Layers },
  { key: "puzzle", icon: Puzzle },
  { key: "compass", icon: Compass },
  { key: "map", icon: MapIcon },
  { key: "rocket", icon: Rocket },
  { key: "archive", icon: Archive },
  { key: "file-text", icon: FileText },
];

const ICON_MAP = new Map<string, LucideIcon>(ICON_PICKS.map((p) => [p.key, p.icon]));

export function iconFor(key: string | null | undefined): LucideIcon {
  return (key && ICON_MAP.get(key)) || Database;
}
