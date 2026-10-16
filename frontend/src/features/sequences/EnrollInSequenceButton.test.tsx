import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import { useAuthStore } from "@/lib/auth";
import { EnrollInSequenceButton } from "./EnrollInSequenceButton";
import SequencesPage from "./SequencesPage";

const SEQUENCES = [
  {
    id: 1,
    name: "Inbound follow-up",
    active: true,
    steps: [
      { id: 11, channel: "EMAIL", delayDays: 0, delayHours: 0, subject: "Hi {{record.firstName}}", body: "Hello", waTemplateParams: [] },
      { id: 12, channel: "WHATSAPP", delayDays: 2, delayHours: 0, waTemplateName: "follow_up", waTemplateParams: [] },
    ],
    enrollmentCounts: { ACTIVE: 1, RESPONDED: 1 },
    activeEnrollments: 1,
  },
  { id: 2, name: "Dormant revival", active: true, steps: [{ id: 21, channel: "TASK", delayDays: 0, delayHours: 0, subject: "Call", waTemplateParams: [] }], enrollmentCounts: {}, activeEnrollments: 0 },
  { id: 3, name: "Switched off", active: false, steps: [], enrollmentCounts: {}, activeEnrollments: 0 },
];

const ENROLLMENTS = [
  {
    id: 7,
    sequenceId: 1,
    sequenceName: "Inbound follow-up",
    relatedType: "Lead",
    relatedId: 42,
    recordName: "Asha Rao",
    status: "RESPONDED",
    currentStep: 1,
    stepCount: 2,
    skippedChannels: [],
    stopReason: "Replied on WhatsApp",
    createdAt: "2026-10-15T10:00:00Z",
  },
];

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return {
    ...actual,
    api: {
      get: vi.fn(async (url: string) => {
        if (url === "/sequences") return { data: SEQUENCES };
        if (url === "/sequence-enrollments") return { data: ENROLLMENTS };
        if (url.endsWith("/enrollments")) return { data: ENROLLMENTS };
        return { data: [] };
      }),
      post: vi.fn(async () => ({ data: { ...ENROLLMENTS[0], id: 8, sequenceId: 2, sequenceName: "Dormant revival", status: "ACTIVE" } })),
      put: vi.fn(),
      delete: vi.fn(),
    },
  };
});

function wrap(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>
  );
}

function signIn(role: string) {
  useAuthStore.setState({ user: { id: 1, name: "Rep", email: "r@x", role } as never, isAuthenticated: true });
}

describe("EnrollInSequenceButton", () => {
  beforeEach(() => vi.clearAllMocks());

  it("lists current enrollments and enrolls the record in an active sequence", async () => {
    signIn("EXECUTIVE");
    wrap(<EnrollInSequenceButton relatedType="Lead" relatedId={42} />);
    fireEvent.click(screen.getByRole("button", { name: /Enroll in sequence/ }));

    expect(await screen.findByText("Responded")).toBeInTheDocument();
    expect(screen.getByText(/Replied on WhatsApp/)).toBeInTheDocument();
    expect(await screen.findByText("Dormant revival")).toBeInTheDocument();
    expect(screen.queryByText("Switched off")).not.toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("radio")[1]);
    fireEvent.click(screen.getByRole("button", { name: /^Enroll$/ }));
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith("/sequences/2/enrollments", { relatedType: "Lead", relatedId: 42 })
    );
  });

  it("is hidden for read-only roles", () => {
    signIn("SUPPORT");
    wrap(<EnrollInSequenceButton relatedType="Contact" relatedId={1} />);
    expect(screen.queryByRole("button", { name: /Enroll in sequence/ })).not.toBeInTheDocument();
  });
});

describe("SequencesPage", () => {
  it("shows sequences, the step timeline and enrollments; managers can build", async () => {
    signIn("MANAGER");
    wrap(<SequencesPage />);
    expect(await screen.findByRole("heading", { name: "Inbound follow-up" })).toBeInTheDocument();
    expect(screen.getByText(/Template follow_up/)).toBeInTheDocument();
    expect(await screen.findByText("Asha Rao")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /New sequence/ }).length).toBeGreaterThan(0);
  });

  it("hides building for reps", async () => {
    signIn("EXECUTIVE");
    wrap(<SequencesPage />);
    expect(await screen.findByRole("heading", { name: "Inbound follow-up" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /New sequence/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Edit/ })).not.toBeInTheDocument();
  });
});
