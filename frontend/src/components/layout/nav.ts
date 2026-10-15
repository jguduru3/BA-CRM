import {
  Activity,
  BarChart3,
  Building2,
  Calendar,
  CheckSquare,
  Database,
  FileText,
  FolderKanban,
  Handshake,
  History,
  LayoutDashboard,
  LayoutGrid,
  ListChecks,
  Megaphone,
  MessageCircle,
  Network,
  Plug,
  Settings,
  StickyNote,
  Target,
  Trash2,
  UserSquare2,
  Users,
  Users2,
  Video,
  Workflow,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { Role } from "@/lib/types";

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  minRole?: Role[];
}
export interface NavGroup {
  label: string;
  items: NavItem[];
}

const MANAGER_UP: Role[] = ["SUPER_ADMIN", "VP_SALES", "MANAGER"];
const ADMIN_ONLY: Role[] = ["SUPER_ADMIN"];
const AUDIT_ROLES: Role[] = ["SUPER_ADMIN", "VP_SALES", "MANAGER"];

export const NAV_GROUPS: NavGroup[] = [
  {
    label: "Overview",
    items: [{ to: "/", label: "Dashboard", icon: LayoutDashboard }],
  },
  {
    label: "Pipeline",
    items: [
      { to: "/leads", label: "Leads", icon: Target },
      { to: "/opportunities", label: "Opportunities", icon: Handshake },
      { to: "/companies", label: "Companies", icon: Building2 },
      { to: "/contacts", label: "Contacts", icon: UserSquare2 },
    ],
  },
  {
    label: "Execution",
    items: [
      { to: "/tasks", label: "Tasks", icon: ListChecks },
      { to: "/projects", label: "Projects", icon: FolderKanban },
      { to: "/todos", label: "Todo Lists", icon: CheckSquare },
      { to: "/calendar", label: "Calendar", icon: Calendar },
      { to: "/meetings", label: "Meetings", icon: Video },
    ],
  },
  {
    label: "Knowledge",
    items: [
      { to: "/documents", label: "Documents", icon: FileText },
      { to: "/notes", label: "Notes", icon: StickyNote },
      { to: "/databases", label: "Databases", icon: Database },
      { to: "/whiteboards", label: "Whiteboards", icon: LayoutGrid },
    ],
  },
  {
    label: "Engagement",
    items: [
      { to: "/activities", label: "Activity Timeline", icon: Activity },
      { to: "/campaigns", label: "Campaigns", icon: Megaphone },
      { to: "/whatsapp", label: "WhatsApp", icon: MessageCircle },
    ],
  },
  {
    label: "Insights",
    items: [
      { to: "/reports", label: "Reports", icon: BarChart3 },
      { to: "/automations", label: "AI Automations", icon: Workflow, minRole: MANAGER_UP },
    ],
  },
  {
    label: "Workspace",
    items: [
      { to: "/team", label: "Team", icon: Users, minRole: MANAGER_UP },
      { to: "/teams", label: "Teams", icon: Users2, minRole: MANAGER_UP },
      { to: "/departments", label: "Departments", icon: Network, minRole: ADMIN_ONLY },
      { to: "/audit-log", label: "Audit Log", icon: History, minRole: AUDIT_ROLES },
      { to: "/integrations", label: "Integrations", icon: Plug, minRole: ADMIN_ONLY },
    ],
  },
];

export const BOTTOM_NAV: NavItem[] = [
  { to: "/settings", label: "Settings", icon: Settings },
  { to: "/trash", label: "Trash", icon: Trash2 },
];

export function canSee(item: NavItem, role: Role | undefined): boolean {
  if (!item.minRole) return true;
  return !!role && item.minRole.includes(role);
}
