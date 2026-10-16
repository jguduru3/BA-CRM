import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import { LeadRoutingSettings } from "./LeadRoutingSettings";

afterEach(() => vi.restoreAllMocks());

describe("LeadRoutingSettings", () => {
  it("loads the rotation, and saves the changed settings", async () => {
    vi.spyOn(api, "get").mockImplementation(async (url: string) =>
      url === "/routing-settings"
        ? ({
            data: {
              enabled: false,
              teamId: null,
              respectTerritory: false,
              lastAssignedUserId: 5,
              updatedAt: null,
              eligibleReps: [{ id: 5, name: "Leslie Knope", role: "EXECUTIVE", territory: "Pawnee" }],
            },
          } as never)
        : ({ data: [{ id: 3, name: "North" }] } as never)
    );
    const put = vi.spyOn(api, "put").mockResolvedValue({
      data: { enabled: true, teamId: 3, respectTerritory: true, lastAssignedUserId: 5, updatedAt: null },
    } as never);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <LeadRoutingSettings open onClose={() => {}} />
      </QueryClientProvider>
    );

    expect(await screen.findByText("Leslie Knope")).toBeInTheDocument();
    expect(screen.getByText("last")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Round-robin assignment"));
    await screen.findByRole("option", { name: "Team: North" });
    fireEvent.change(screen.getByLabelText("Rotate among"), { target: { value: "3" } });
    fireEvent.click(screen.getByLabelText(/respect territories/i));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(put).toHaveBeenCalledWith("/routing-settings", { enabled: true, teamId: 3, respectTerritory: true })
    );
  });
});
