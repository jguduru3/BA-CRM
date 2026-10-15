// Mirrors of the backend's Java entities/DTOs (see src/main/java/.../entity,
// .../dto). Field names match the JSON Jackson produces (camelCase getters).

export type Role =
  | "SUPER_ADMIN"
  | "VP_SALES"
  | "MANAGER"
  | "EXECUTIVE"
  | "INSIDE_SALES"
  | "FIELD_SALES"
  | "SUPPORT";

export interface User {
  id: number;
  organizationId: number;
  name: string;
  email: string;
  role: Role;
  reportsToId: number | null;
  territory: string | null;
  initials: string | null;
  color: string | null;
  active: boolean;
  mustChangePassword?: boolean;
  lastLoginAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Organization {
  id: number;
  name: string;
  slug: string;
  plan: string;
  industry: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ListResponse<T> {
  count: number;
  data: T[];
}

export interface Lead {
  id: number;
  organizationId: number;
  name: string;
  email: string | null;
  phone: string | null;
  companyId: number | null;
  ownerId: number | null;
  stage: string;
  source: string;
  priority: string;
  value: number;
  notes: string | null;
  tags: string | null;
  convertedAt: string | null;
  convertedOppId: number | null;
  followUpAt: string | null;
  address: string | null;
  businessName: string | null;
  category: string | null;
  leadType: string | null;
  industry: string | null;
  geography: string | null;
  employeeBand: string | null;
  revenueBand: string | null;
  requirement: string | null;
  painPoint: string | null;
  technologyUsed: string | null;
  decisionMaker: string | null;
  leadScore: number | null;
  leadScoreOverrideReason: string | null;
  followUpRescheduleCount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Company {
  id: number;
  organizationId: number;
  name: string;
  industry: string | null;
  size: string | null;
  website: string | null;
  phone: string | null;
  email: string | null;
  location: string | null;
  revenue: number | null;
  status: string;
  notes: string | null;
  ownerId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Contact {
  id: number;
  organizationId: number;
  name: string;
  title: string | null;
  email: string | null;
  phone: string | null;
  companyId: number | null;
  ownerId: number | null;
  status: string;
  source: string | null;
  notes: string | null;
  tags: string | null;
  linkedin: string | null;
  lastContactedAt: string | null;
  categoryId: number | null;
  industry: string | null;
  country: string | null;
  state: string | null;
  natureOfBusiness: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ContactCategory {
  id: number;
  organizationId: number;
  name: string;
  description: string | null;
  color: string | null;
  icon: string | null;
  // Jackson/Lombok strips the "is" prefix from an already-"is"-named boolean
  // field's JSON key (isSystem -> "system", isActive -> "active") — verified
  // against the actual serializer, not assumed. See AiAutomation/TodoItem too.
  system: boolean;
  active: boolean;
  industry: string | null;
  country: string | null;
  state: string | null;
}

export interface Opportunity {
  id: number;
  organizationId: number;
  name: string;
  companyId: number | null;
  contactId: number | null;
  ownerId: number | null;
  stage: string;
  value: number;
  probability: number;
  closeDate: string | null;
  notes: string | null;
  serviceProduct: string | null;
  competitors: string | null;
  currentVendor: string | null;
  proposalStatus: string | null;
  negotiationStatus: string | null;
  poStatus: string | null;
  sourceLeadId: number | null;
  sourcedByUserId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Task {
  id: number;
  organizationId: number;
  title: string;
  priority: string;
  status: string;
  assigneeId: number | null;
  relatedType: string | null;
  relatedId: number | null;
  dueDate: string | null;
  completedAt: string | null;
  projectId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Activity {
  id: number;
  organizationId: number;
  type: string;
  subject: string | null;
  body: string | null;
  userId: number | null;
  relatedType: string | null;
  relatedId: number | null;
  outcome: string | null;
  scheduledAt: string | null;
  completedAt: string | null;
  nextFollowUpAt: string | null;
  correctionForId: number | null;
  durationMinutes: number | null;
  createdAt: string;
  masked?: boolean;
}

export interface PipelineResponse {
  groupBy: string;
  columns: Record<string, Lead[]>;
}

export interface OppPipelineRow {
  stage: string;
  count: number;
  value: number | null;
}

export interface FunnelRow {
  stage: string;
  count: number;
}

export interface LeadSourceRow {
  source: string;
  count: number;
}

export interface TeamStatRow {
  id: number;
  name: string;
  role: Role;
  initials: string | null;
  color: string | null;
  leadsOwned: number;
  pipelineValue: number;
  wonValue: number;
}

export interface DashboardStats {
  totalLeads: number;
  activeLeads: number;
  totalOppValue: number;
  wonValue: number;
  wonOpps: number;
  openTasks: number;
  overdueTasks: number;
  scope: string;
}

export interface Meeting {
  id: number;
  organizationId: number;
  title: string;
  description: string | null;
  location: string | null;
  meetingUrl: string | null;
  status: string;
  startAt: string;
  endAt: string | null;
  durationMinutes: number | null;
  organizerId: number | null;
  relatedType: string | null;
  relatedId: number | null;
  notes: string | null;
  outcome: string | null;
  createdBy: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Document {
  id: number;
  organizationId: number;
  title: string;
  contentJson: string | null;
  relatedType: string | null;
  relatedId: number | null;
  ownerId: number | null;
  createdBy: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Note {
  id: number;
  organizationId: number;
  title: string | null;
  body: string;
  relatedType: string | null;
  relatedId: number | null;
  pinned: boolean;
  createdBy: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Team {
  id: number;
  organizationId: number;
  name: string;
  description: string | null;
  leaderId: number | null;
  departmentId: number | null;
  color: string | null;
  active: boolean;
}

export interface Project {
  id: number;
  organizationId: number;
  name: string;
  description: string | null;
  status: string;
  priority: string;
  ownerId: number;
  startDate: string | null;
  dueDate: string | null;
  completedAt: string | null;
  budget: number | null;
  tags: string | null;
  coverColor: string | null;
  icon: string | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface TodoList {
  id: number;
  organizationId: number;
  name: string;
  description: string | null;
  color: string | null;
  icon: string | null;
  shared: boolean;
  ownerId: number;
  position: number;
  createdAt: string;
  updatedAt: string;
}

export interface TodoItem {
  id: number;
  organizationId: number;
  listId: number | null;
  title: string;
  notes: string | null;
  done: boolean;
  pinned: boolean;
  priority: string;
  dueAt: string | null;
  doneAt: string | null;
  position: number;
  parentId: number | null;
  relatedType: string | null;
  relatedId: number | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface AppNotification {
  id: number;
  organizationId: number;
  userId: number;
  type: string;
  title: string;
  body: string | null;
  relatedType: string | null;
  relatedId: number | null;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

export interface AuditEvent {
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

export interface CalendarEvent {
  id: number;
  organizationId: number;
  title: string;
  description: string | null;
  startAt: string;
  endAt: string | null;
  allDay: boolean;
  eventType: string;
  relatedType: string | null;
  relatedId: number | null;
  colorHex: string | null;
  location: string | null;
  recurrenceRule: string | null;
  ownerId: number;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface Campaign {
  id: number;
  organizationId: number;
  name: string;
  channel?: string;
  status: string;
  [key: string]: unknown;
}

export interface Department {
  id: number;
  organizationId: number;
  name: string;
  description?: string | null;
  [key: string]: unknown;
}
