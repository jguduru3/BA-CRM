import { lazy, Suspense } from "react";
import { Route, Routes } from "react-router-dom";
import { AppShell } from "@/components/layout/AppShell";
import { PageLoader } from "@/components/layout/PageLoader";
import { ProtectedRoute } from "@/components/layout/ProtectedRoute";
import { Toaster } from "@/components/ui/Toaster";
import { LoginPage } from "@/features/auth/LoginPage";
import { useAuthStore } from "@/lib/auth";

const DashboardPage = lazy(() => import("@/features/dashboard/DashboardPage"));
const LeadsPage = lazy(() => import("@/features/leads/LeadsPage"));
const CompaniesPage = lazy(() => import("@/features/companies/CompaniesPage"));
const ContactsPage = lazy(() => import("@/features/contacts/ContactsPage"));
const OpportunitiesPage = lazy(() => import("@/features/opportunities/OpportunitiesPage"));
const TasksPage = lazy(() => import("@/features/tasks/TasksPage"));
const ActivitiesPage = lazy(() => import("@/features/activities/ActivitiesPage"));
const ReportsPage = lazy(() => import("@/features/reports/ReportsPage"));
const ForecastPage = lazy(() => import("@/features/forecast/ForecastPage"));
const TeamPage = lazy(() => import("@/features/team/TeamPage"));
const TeamsAdminPage = lazy(() => import("@/features/team/TeamsAdminPage"));
const DepartmentsPage = lazy(() => import("@/features/team/DepartmentsPage"));
const ProjectsPage = lazy(() => import("@/features/projects/ProjectsPage"));
const TodosPage = lazy(() => import("@/features/todos/TodosPage"));
const CalendarPage = lazy(() => import("@/features/calendar/CalendarPage"));
const MeetingsPage = lazy(() => import("@/features/meetings/MeetingsPage"));
const DocumentsPage = lazy(() => import("@/features/documents/DocumentsPage"));
const NotesPage = lazy(() => import("@/features/notes/NotesPage"));
const DatabasesPage = lazy(() => import("@/features/databases/DatabasesPage"));
const DatabaseDetailPage = lazy(() => import("@/features/databases/DatabaseDetailPage"));
const WhiteboardsPage = lazy(() => import("@/features/whiteboard/WhiteboardsPage"));
const WhiteboardDetailPage = lazy(() => import("@/features/whiteboard/WhiteboardDetailPage"));
const AutomationsPage = lazy(() => import("@/features/automations/AutomationsPage"));
const CampaignsPage = lazy(() => import("@/features/campaigns/CampaignsPage"));
const WhatsAppPage = lazy(() => import("@/features/whatsapp/WhatsAppPage"));
const AuditLogPage = lazy(() => import("@/features/admin/AuditLogPage"));
const SettingsPage = lazy(() => import("@/features/settings/SettingsPage"));
const TrashPage = lazy(() => import("@/features/trash/TrashPage"));
const ChangePasswordPage = lazy(() => import("@/features/auth/ChangePasswordPage"));
const IntegrationsPage = lazy(() => import("@/features/integrations/IntegrationsPage"));
const AdminConsolePage = lazy(() => import("@/features/admin/AdminConsolePage"));
const SequencesPage = lazy(() => import("@/features/sequences/SequencesPage"));
const NotFoundPage = lazy(() => import("@/features/misc/NotFoundPage"));
const DuplicatesPage = lazy(() => import("@/features/duplicates/DuplicatesPage"));

export default function App() {
  const signedIn = useAuthStore((s) => s.isAuthenticated);
  return (
    <>
      <Suspense fallback={<PageLoader />}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/change-password" element={<ChangePasswordPage />} />

          <Route element={<ProtectedRoute />}>
            <Route element={<AppShell />}>
              <Route index element={<DashboardPage />} />
              <Route path="/leads" element={<LeadsPage />} />
              <Route path="/companies" element={<CompaniesPage />} />
              <Route path="/contacts" element={<ContactsPage />} />
              <Route path="/opportunities" element={<OpportunitiesPage />} />
              <Route path="/tasks" element={<TasksPage />} />
              <Route path="/activities" element={<ActivitiesPage />} />
              <Route path="/reports" element={<ReportsPage />} />
              <Route path="/forecast" element={<ForecastPage />} />
              <Route path="/team" element={<TeamPage />} />
              <Route path="/teams" element={<TeamsAdminPage />} />
              <Route path="/departments" element={<DepartmentsPage />} />
              <Route path="/projects" element={<ProjectsPage />} />
              <Route path="/todos" element={<TodosPage />} />
              <Route path="/calendar" element={<CalendarPage />} />
              <Route path="/meetings" element={<MeetingsPage />} />
              <Route path="/documents" element={<DocumentsPage />} />
              <Route path="/notes" element={<NotesPage />} />
              <Route path="/databases" element={<DatabasesPage />} />
              <Route path="/databases/:id" element={<DatabaseDetailPage />} />
              <Route path="/whiteboards" element={<WhiteboardsPage />} />
              <Route path="/whiteboards/:id" element={<WhiteboardDetailPage />} />
              <Route path="/automations" element={<AutomationsPage />} />
              <Route path="/campaigns" element={<CampaignsPage />} />
              <Route path="/whatsapp" element={<WhatsAppPage />} />
              <Route path="/audit-log" element={<AuditLogPage />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/trash" element={<TrashPage />} />
              <Route path="/integrations" element={<IntegrationsPage />} />
              <Route path="/admin" element={<AdminConsolePage />} />
              <Route path="/sequences" element={<SequencesPage />} />
              <Route path="/duplicates" element={<DuplicatesPage />} />
              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Route>

        </Routes>
      </Suspense>
      <div className={`toaster-host ${signedIn ? "above-bottom-nav" : ""}`}>
        <Toaster />
      </div>
    </>
  );
}
