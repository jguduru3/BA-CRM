import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import type { Role, User } from "@/lib/types";
import { DuplicateDialog } from "./DuplicateDialog";
import DuplicatesPage, { GroupCard } from "./DuplicatesPage";
import { conflictError, differingFields, DuplicateError, isDuplicateError, matchLabel, recordHref, saveChecked } from "./duplicates";

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

function wrap(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  useAuthStore.setState({ token: null, user: null, isAuthenticated: false });
});

const candidates = [{ id: 7, name: "Dana Scully", matchedOn: ["email", "name"], score: 1 }];

describe("duplicate helpers", () => {
  it("turns duplicate 409 bodies into DuplicateError and others into ApiError", () => {
    const warn = conflictError({ error: "looks like a duplicate", code: "DUPLICATE_WARNING", type: "lead", candidates }, "lead");
    expect(isDuplicateError(warn)).toBe(true);
    expect((warn as DuplicateError).blocked).toBe(false);
    expect((warn as DuplicateError).candidates[0].id).toBe(7);
    const block = conflictError({ error: "no", code: "DUPLICATE_BLOCKED", candidates: [] }, "company");
    expect((block as DuplicateError).blocked).toBe(true);
    expect((block as DuplicateError).type).toBe("company");
    const stale = conflictError({ error: "changed", code: "STALE_UPDATE" }, "lead");
    expect(isDuplicateError(stale)).toBe(false);
    expect(stale).toBeInstanceOf(ApiError);
    expect(stale.code).toBe("STALE_UPDATE");
  });

  it("saveChecked sends the bypass flag and surfaces the candidates", async () => {
    const request = vi.spyOn(api, "request");
    request.mockResolvedValueOnce({ status: 409, data: { code: "DUPLICATE_WARNING", error: "dup", candidates } } as never);
    await expect(saveChecked("post", "/leads", { name: "D" }, "lead")).rejects.toBeInstanceOf(DuplicateError);
    request.mockResolvedValueOnce({ status: 201, data: { id: 9 } } as never);
    await expect(saveChecked("post", "/leads", { name: "D" }, "lead", true)).resolves.toEqual({ id: 9 });
    expect(request.mock.calls[1][0]).toMatchObject({ method: "post", url: "/leads", params: { allowDuplicate: true } });
    expect(request.mock.calls[0][0].params).toBeUndefined();
  });

  it("labels, links and highlights differences", () => {
    expect(recordHref("company", 3)).toBe("/companies?open=3");
    expect(matchLabel("phone")).toBe("Same phone");
    const diff = differingFields([{ email: "a@x.com", phone: null }, { email: "a@x.com", phone: "1" }], ["email", "phone"]);
    expect([...diff]).toEqual(["phone"]);
  });
});

describe("DuplicateDialog", () => {
  it("lists candidates and saves anyway on a warning", () => {
    const onSave = vi.fn();
    wrap(
      <DuplicateDialog
        error={new DuplicateError("dup", "DUPLICATE_WARNING", "lead", candidates)}
        onClose={() => {}}
        onSaveAnyway={onSave}
      />
    );
    expect(screen.getByText("Possible duplicate lead")).toBeInTheDocument();
    expect(screen.getByText("Dana Scully")).toBeInTheDocument();
    expect(screen.getByText("Same email")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /open existing/i })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Save anyway" }));
    expect(onSave).toHaveBeenCalled();
  });

  it("offers no bypass when the rule blocks", () => {
    wrap(
      <DuplicateDialog
        error={new DuplicateError("blocked", "DUPLICATE_BLOCKED", "company", [])}
        onClose={() => {}}
        onSaveAnyway={() => {}}
      />
    );
    expect(screen.getByText("Duplicate company not allowed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save anyway" })).toBeNull();
  });
});

describe("Duplicates page", () => {
  const group = {
    key: "lead:1",
    score: 1,
    matchedOn: ["email"],
    records: [
      { id: 1, name: "Ann Perkins", email: "ann@pawnee.gov", phone: null, createdAt: "2026-01-01T00:00:00Z" },
      { id: 2, name: "Ann P.", email: "ann@pawnee.gov", phone: "+15550100", createdAt: "2026-02-01T00:00:00Z" },
    ],
  };

  it("compares side by side and confirms the irreversible merge", async () => {
    signIn("MANAGER");
    const post = vi.spyOn(api, "post").mockResolvedValue({ data: { ok: true } } as never);
    wrap(<GroupCard type="lead" group={group} canMerge />);
    expect(screen.getByText("Ann Perkins")).toBeInTheDocument();
    expect(screen.getByText("+15550100")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /merge 2 records/i }));
    expect(await screen.findByText(/cannot be undone/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirm" }));
    await waitFor(() => expect(post).toHaveBeenCalledWith("/leads/1/merge", { secondaryIds: [2] }));
  });

  it("hides merging for roles that cannot merge", () => {
    wrap(<GroupCard type="lead" group={group} canMerge={false} />);
    expect(screen.queryByRole("button", { name: /merge/i })).toBeNull();
  });

  it("shows a retryable error when loading fails", async () => {
    signIn("MANAGER");
    vi.spyOn(api, "get").mockRejectedValue(new ApiError("boom", 500));
    wrap(<DuplicatesPage />);
    expect(await screen.findByText("Couldn't load duplicates")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /try again/i })).toBeInTheDocument();
  });
});
