import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useMobileNavStore } from "@/components/layout/mobileNavStore";
import { ProtectedRoute } from "@/components/layout/ProtectedRoute";
import { LoginPage } from "@/features/auth/LoginPage";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { queryClient } from "@/lib/queryClient";
import { useThemeStore } from "@/lib/theme";
import { toast, useToastStore } from "@/lib/toast";
import type { User } from "@/lib/types";

const USER: User = {
  id: 7,
  organizationId: 1,
  name: "Ada Lovelace",
  email: "ada@example.com",
  role: "EXECUTIVE",
  reportsToId: null,
  territory: null,
  initials: "AL",
  color: null,
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

beforeEach(() => {
  localStorage.clear();
  useAuthStore.setState({ token: null, user: null, isAuthenticated: false });
});

afterEach(() => {
  vi.restoreAllMocks();
  queryClient.clear();
  useToastStore.getState().clear();
});

describe("logout", () => {
  it("clears the query cache, toasts and UI stores but keeps the theme", () => {
    useAuthStore.getState().setSession("tok-123", USER);
    useThemeStore.getState().setMode("dark");
    queryClient.setQueryData(["leads"], { count: 1, data: [{ id: 1 }] });
    toast.success("Saved");
    useMobileNavStore.getState().setOpen(true);

    expect(queryClient.getQueryData(["leads"])).toBeDefined();
    expect(useToastStore.getState().items).toHaveLength(1);

    useAuthStore.getState().logout();

    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(localStorage.getItem("bettercrm.token")).toBeNull();
    expect(queryClient.getQueryData(["leads"])).toBeUndefined();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(useToastStore.getState().items).toHaveLength(0);
    expect(useMobileNavStore.getState().open).toBe(false);
    // Theme is a device preference, not user data.
    expect(useThemeStore.getState().mode).toBe("dark");
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });
});

function WhereAmI() {
  const loc = useLocation();
  return <div data-testid="location">{loc.pathname + loc.search}</div>;
}

describe("login", () => {
  it("returns the user to the page they originally tried to open", async () => {
    const post = vi.spyOn(api, "post").mockResolvedValue({
      data: { token: "tok-abc", user: USER, mustChangePassword: false },
    });

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/login?next=${encodeURIComponent("/leads?open=4")}`]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="*" element={<WhereAmI />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    );

    fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: " ada@example.com " } });
    fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: "S3cure-passw0rd" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/leads?open=4"));
    expect(post).toHaveBeenCalledWith("/auth/login", { email: "ada@example.com", password: "S3cure-passw0rd" });
    expect(useAuthStore.getState().token).toBe("tok-abc");
    expect(screen.queryByRole("heading", { name: /welcome back/i })).not.toBeInTheDocument();
  });

  it("round-trips through ProtectedRoute: /leads -> /login?next=/leads -> /leads", async () => {
    vi.spyOn(api, "post").mockResolvedValue({
      data: { token: "tok-abc", user: USER, mustChangePassword: false },
    });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/leads"]}>
          <Routes>
            <Route path="/login" element={<><LoginPage /><WhereAmI /></>} />
            <Route element={<ProtectedRoute />}>
              <Route path="/leads" element={<div data-testid="leads-page"><WhereAmI /></div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(await screen.findByRole("heading", { name: /welcome back/i })).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/login?next=%2Fleads");

    fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ada@example.com" } });
    fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: "S3cure-passw0rd" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByTestId("leads-page")).toHaveTextContent("/leads");
  });

  it("ignores an off-site next target", async () => {
    vi.spyOn(api, "post").mockResolvedValue({
      data: { token: "tok-abc", user: USER, mustChangePassword: false },
    });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/login?next=%2F%2Fevil.example"]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="*" element={<WhereAmI />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    );
    fireEvent.change(screen.getByLabelText(/email address/i), { target: { value: "ada@example.com" } });
    fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: "S3cure-passw0rd" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/$/));
  });
});
