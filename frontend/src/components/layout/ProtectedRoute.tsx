import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuthStore } from "@/lib/auth";

export function ProtectedRoute() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const mustChange = useAuthStore((s) => !!s.user?.mustChangePassword);
  const location = useLocation();
  if (!isAuthenticated) {
    const next = location.pathname + location.search;
    return <Navigate to={next && next !== "/" ? `/login?next=${encodeURIComponent(next)}` : "/login"} replace />;
  }
  if (mustChange) {
    return <Navigate to="/change-password" replace />;
  }
  return <Outlet />;
}
