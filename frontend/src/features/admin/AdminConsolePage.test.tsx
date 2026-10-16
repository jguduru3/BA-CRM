import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import AdminConsolePage from "./AdminConsolePage";
import { useAuthStore } from "@/lib/auth";
import type { User } from "@/lib/types";

function renderAt(role: User["role"]) {
  useAuthStore.setState({
    token: "t",
    isAuthenticated: true,
    user: { id: 1, organizationId: 1, name: "A", email: "a@x.com", role, reportsToId: null, territory: null, initials: "A", color: null, active: true, createdAt: "", updatedAt: "" },
  });
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={["/admin?tab=configuration"]}>
        <Routes>
          <Route path="/admin" element={<AdminConsolePage />} />
          <Route path="/" element={<p>home</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe("AdminConsolePage", () => {
  afterEach(() => useAuthStore.setState({ token: null, user: null, isAuthenticated: false }));

  it("sends non-admins back to the dashboard", () => {
    renderAt("VP_SALES");
    expect(screen.getByText("home")).toBeInTheDocument();
  });

  it("shows the configuration hub for Super Admins with links to every setting", () => {
    renderAt("SUPER_ADMIN");
    expect(screen.getByRole("heading", { name: "Admin console" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /API keys & webhooks/i })).toHaveAttribute("href", "/integrations");
    expect(screen.getByRole("link", { name: /AI assistant/i })).toHaveAttribute("href", "/settings?tab=ai");
    expect(screen.getByRole("link", { name: /Duplicate rules/i })).toHaveAttribute("href", "/duplicates");
  });
});
