import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppShell } from "@/components/layout/AppShell";
import { useMobileNavStore } from "@/components/layout/mobileNavStore";
import AuditLogPage from "@/features/admin/AuditLogPage";
import { actionTone, diffFields, filterParams, toAuditPage, EMPTY_FILTERS } from "@/features/admin/auditDiff";
import IntegrationsPage from "@/features/integrations/IntegrationsPage";
import DepartmentsPage from "@/features/team/DepartmentsPage";
import { api, ApiError } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import type { Role, User } from "@/lib/types";

function signIn(role: Role) {
  const user: User = {
    id: 1,
    organizationId: 1,
    name: "Grace Hopper",
    email: "grace@example.com",
    role,
    reportsToId: null,
    territory: null,
    initials: "GH",
    color: null,
    active: true,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
  };
  useAuthStore.setState({ token: "t", user, isAuthenticated: true });
}

function renderAt(ui: React.ReactNode, path = "/") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="*" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => localStorage.clear());
afterEach(() => {
  vi.restoreAllMocks();
  useAuthStore.setState({ token: null, user: null, isAuthenticated: false });
  useMobileNavStore.setState({ open: false });
});

describe("audit diff helpers", () => {
  it("lists only the fields that changed", () => {
    const changes = diffFields({ name: "Acme", stage: "New", value: 10 }, { name: "Acme", stage: "Won", owner: 4 });
    expect(changes).toEqual([
      { key: "owner", before: undefined, after: 4, kind: "added" },
      { key: "stage", before: "New", after: "Won", kind: "changed" },
      { key: "value", before: 10, after: undefined, kind: "removed" },
    ]);
    expect(diffFields("plain text", { a: 1 })).toBeNull();
  });

  it("omits empty filters from the query string", () => {
    expect(filterParams({ ...EMPTY_FILTERS, resource: "leads", from: "2026-01-01", q: "  " }).toString()).toBe(
      "resource=leads&from=2026-01-01"
    );
  });

  it("accepts paged and legacy list payloads", () => {
    expect(toAuditPage({ count: 120, data: [], page: 1, size: 50, totalPages: 3 }, 1, 50).totalPages).toBe(3);
    expect(toAuditPage({ count: 120, data: [] }, 0, 50).totalPages).toBe(3);
    expect(toAuditPage([], 0, 50).totalPages).toBe(1);
  });

  it("gives destructive actions a critical tone", () => {
    expect(actionTone("delete")).toBe("critical");
    expect(actionTone("permission_denied")).toBe("critical");
    expect(actionTone("login_failed")).toBe("critical");
    expect(actionTone("create")).toBe("good");
    expect(actionTone("update")).toBe("brand");
  });
});

describe("mobile bottom navigation", () => {
  it("shows the primary tabs and opens the full menu from More", async () => {
    signIn("EXECUTIVE");
    vi.spyOn(api, "get").mockResolvedValue({ data: [] });
    renderAt(<AppShell />, "/leads");

    const bar = screen.getByRole("navigation", { name: "Primary" });
    expect(within(bar).getByRole("link", { name: /dashboard/i })).toHaveAttribute("href", "/");
    expect(within(bar).getByRole("link", { name: /leads/i })).toHaveClass("is-active");
    expect(within(bar).getByRole("link", { name: /tasks/i })).toHaveAttribute("href", "/tasks");

    expect(screen.queryByRole("dialog", { name: "Navigation" })).not.toBeInTheDocument();
    fireEvent.click(within(bar).getByRole("button", { name: /more/i }));
    const drawer = await screen.findByRole("dialog", { name: "Navigation" });
    expect(within(drawer).getByRole("link", { name: /settings/i })).toBeInTheDocument();

    fireEvent.click(within(drawer).getByRole("button", { name: /close menu/i }));
    expect(screen.queryByRole("dialog", { name: "Navigation" })).not.toBeInTheDocument();

    // The Topbar hamburger drives the same drawer.
    fireEvent.click(screen.getByRole("button", { name: /open menu/i }));
    expect(await screen.findByRole("dialog", { name: "Navigation" })).toBeInTheDocument();
  });
});

describe("departments page", () => {
  it("shows a retryable error instead of an endless skeleton", async () => {
    signIn("SUPER_ADMIN");
    const get = vi.spyOn(api, "get").mockImplementation(async (url: string) => {
      if (url === "/departments") throw new ApiError("Server unavailable", 503);
      return { data: [] };
    });
    renderAt(<DepartmentsPage />);
    expect(await screen.findByText("Server unavailable")).toBeInTheDocument();

    get.mockImplementation(async (url: string) =>
      url === "/departments"
        ? { data: [{ id: 1, organizationId: 1, name: "Sales", description: "Revenue", headId: 1, parentId: null, active: true }] }
        : { data: [{ id: 1, name: "Grace Hopper", color: null, active: true }] }
    );
    fireEvent.click(screen.getByRole("button", { name: /try again/i }));
    expect((await screen.findAllByText("Sales")).length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: /new department/i })).toBeInTheDocument();
  });

  it("is read-only for non-admins", async () => {
    signIn("EXECUTIVE");
    vi.spyOn(api, "get").mockImplementation(async (url: string) =>
      url === "/departments"
        ? { data: [{ id: 1, organizationId: 1, name: "Sales", description: null, headId: null, parentId: null, active: true }] }
        : { data: [] }
    );
    renderAt(<DepartmentsPage />);
    expect((await screen.findAllByText("Sales")).length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: /new department/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /delete sales/i })).not.toBeInTheDocument();
  });

  it("surfaces the server's 409 explanation when a delete is refused", async () => {
    signIn("SUPER_ADMIN");
    vi.spyOn(api, "get").mockImplementation(async (url: string) =>
      url === "/departments"
        ? { data: [{ id: 3, organizationId: 1, name: "Sales", description: null, headId: null, parentId: null, active: true }] }
        : { data: [] }
    );
    const del = vi
      .spyOn(api, "delete")
      .mockRejectedValue(new ApiError("department still has teams assigned; reassign them first", 409));
    const { useToastStore } = await import("@/lib/toast");
    renderAt(<DepartmentsPage />);
    fireEvent.click((await screen.findAllByRole("button", { name: /delete sales/i }))[0]);
    fireEvent.click(await screen.findByRole("button", { name: /^confirm$/i }));
    await waitFor(() => expect(del).toHaveBeenCalledWith("/departments/3"));
    await waitFor(() =>
      expect(useToastStore.getState().items.map((t) => t.message)).toContain("department still has teams assigned; reassign them first")
    );
  });
});

describe("restricted pages", () => {
  it("explains that integrations are Super Admin only", () => {
    signIn("MANAGER");
    renderAt(<IntegrationsPage />);
    expect(screen.getByText(/super admin only/i)).toBeInTheDocument();
  });

  it("explains that the audit log is for managers and above", () => {
    signIn("EXECUTIVE");
    renderAt(<AuditLogPage />);
    expect(screen.getByText(/managers and admins only/i)).toBeInTheDocument();
  });

  it("loads a page of audit events with server-side paging params", async () => {
    signIn("SUPER_ADMIN");
    const get = vi.spyOn(api, "get").mockImplementation(async (url: string) => {
      if (url.startsWith("/audit-events?")) {
        return {
          data: {
            count: 1,
            page: 0,
            size: 50,
            totalPages: 1,
            data: [
              {
                id: 9,
                organizationId: 1,
                userId: 1,
                userName: "Grace Hopper",
                action: "update",
                resource: "leads",
                resourceId: "4",
                resourceName: "Acme deal",
                beforeJson: '{"stage":"New"}',
                afterJson: '{"stage":"Won"}',
                ipAddress: null,
                userAgent: null,
                createdAt: "2026-10-01T10:00:00Z",
              },
            ],
          },
        };
      }
      if (url === "/audit-events/facets") return { data: { resources: ["leads"], actions: ["update"] } };
      return { data: [] };
    });
    renderAt(<AuditLogPage />);
    expect((await screen.findAllByText("Acme deal")).length).toBeGreaterThan(0);
    expect(get.mock.calls.some(([u]) => String(u).includes("page=0") && String(u).includes("size=50"))).toBe(true);
    expect(screen.getByText(/page 1 of 1/i)).toBeInTheDocument();
  });
});
