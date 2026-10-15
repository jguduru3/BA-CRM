import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import App from "@/App";
import { queryClient } from "@/lib/queryClient";
import { safeNext } from "@/features/auth/LoginPage";
import { passwordChecks } from "@/features/auth/passwordRules";

describe("app shell", () => {
  it("sends signed-out users to the sign-in screen with the brand logo", async () => {
    localStorage.clear();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/leads"]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(await screen.findByRole("heading", { name: /welcome back/i })).toBeInTheDocument();
    expect(screen.getAllByAltText("Better Analytics").length).toBeGreaterThan(0);
    expect(screen.getByLabelText(/email address/i)).toHaveValue("");
  });
});

describe("safeNext (post-login redirect)", () => {
  it("allows in-app paths", () => {
    expect(safeNext("/leads?open=4")).toBe("/leads?open=4");
  });
  it.each(["https://evil.example", "//evil.example", String.raw`/\evil.example`, "javascript:alert(1)", null, "/login"])(
    "rejects %s",
    (v) => expect(safeNext(v)).toBe("/")
  );
});

describe("password rules", () => {
  it("mirror the server policy", () => {
    expect(passwordChecks("short1").every((c) => c.ok)).toBe(false);
    expect(passwordChecks("onlyletterslong").every((c) => c.ok)).toBe(false);
    expect(passwordChecks("GoodPassw0rd").every((c) => c.ok)).toBe(true);
    expect(passwordChecks("aliceSecret123", "alice@x.com").every((c) => c.ok)).toBe(false);
  });
});
