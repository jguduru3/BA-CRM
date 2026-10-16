import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { HBarChart } from "@/components/charts/HBarChart";
import ForecastPage from "@/features/forecast/ForecastPage";
import { periodLabel, periodStart, shiftPeriod, type ForecastBucket, type ForecastResponse } from "@/features/forecast/forecastTypes";
import { HealthBadge, sortedFactors } from "@/features/opportunities/HealthBadge";
import { healthTone } from "@/features/opportunities/pipelineTypes";
import { api } from "@/lib/api";
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

function renderPage(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

const cell = (count: number, value: number | null, weighted: number | null) => ({ count, value, weighted });

function bucket(masked: boolean): ForecastBucket {
  const v = (n: number) => (masked ? null : n);
  return {
    start: "2027-01-01",
    end: "2027-03-31",
    label: "Q1 2027",
    totals: {
      NOT_FORECASTED: cell(1, v(900), v(0)),
      PIPELINE: cell(1, v(1000), v(300)),
      BEST_CASE: cell(1, v(2000), v(1200)),
      COMMIT: cell(1, v(500), v(400)),
      CLOSED_WON: cell(1, v(700), v(700)),
    },
    total: cell(5, v(5100), v(2600)),
    cumulative: { NOT_FORECASTED: v(5100), PIPELINE: v(4200), BEST_CASE: v(3200), COMMIT: v(1200), CLOSED_WON: v(700) },
    cumulativeWeighted: { NOT_FORECASTED: v(2600), PIPELINE: v(2600), BEST_CASE: v(2300), COMMIT: v(1100), CLOSED_WON: v(700) },
    commitAndWon: v(1200),
    closedWon: v(700),
    quota: v(2400),
    attainment: masked ? null : 50,
    wonAttainment: masked ? null : 29.2,
    groups: masked
      ? []
      : [
          {
            id: 7,
            name: "Ada Lovelace",
            totals: {
              NOT_FORECASTED: cell(0, 0, 0),
              PIPELINE: cell(1, 1000, 300),
              BEST_CASE: cell(0, 0, 0),
              COMMIT: cell(1, 500, 400),
              CLOSED_WON: cell(1, 700, 700),
            },
            total: cell(3, 2200, 1400),
            cumulative: { NOT_FORECASTED: 2200, PIPELINE: 2200, BEST_CASE: 1200, COMMIT: 1200, CLOSED_WON: 700 },
            cumulativeWeighted: { NOT_FORECASTED: 1400, PIPELINE: 1400, BEST_CASE: 1100, COMMIT: 1100, CLOSED_WON: 700 },
            commitAndWon: 1200,
            closedWon: 700,
            quota: 2400,
            attainment: 50,
            wonAttainment: 29.2,
          },
        ],
  };
}

function forecast(masked: boolean): ForecastResponse {
  return {
    period: "quarter",
    start: "2027-01-01",
    periods: 3,
    groupBy: masked ? "none" : "owner",
    valuesVisible: !masked,
    categories: [],
    buckets: [bucket(masked)],
  };
}

// jsdom lacks these; the chart palette and recharts' ResponsiveContainer use them.
beforeAll(() => {
  if (!window.matchMedia) {
    Object.defineProperty(window, "matchMedia", {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }),
    });
  }
  if (!("ResizeObserver" in window)) {
    class RO {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    Object.defineProperty(window, "ResizeObserver", { writable: true, value: RO });
  }
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("forecast period helpers", () => {
  it("snaps to the month / quarter start and shifts by whole periods", () => {
    expect(periodStart("month", new Date(2027, 0, 17))).toBe("2027-01-01");
    expect(periodStart("quarter", new Date(2027, 4, 9))).toBe("2027-04-01");
    expect(shiftPeriod("quarter", "2027-10-01", 1)).toBe("2028-01-01");
    expect(shiftPeriod("month", "2027-01-01", -1)).toBe("2026-12-01");
    expect(periodLabel("quarter", "2027-04-01")).toBe("Q2 2027");
  });
});

describe("deal health badge", () => {
  it("shows the score and explains it on hover, deductions first", () => {
    const factors = [
      { key: "contact", label: "Contact linked", points: 0, detail: "A contact person is linked" },
      { key: "stage_time", label: "Stalled in stage", points: -25, detail: "40 days in Proposal, more than twice the usual 10 days" },
    ];
    expect(sortedFactors(factors)[0].key).toBe("stage_time");
    expect(healthTone(80)).toBe("good");
    expect(healthTone(45)).toBe("warning");
    expect(healthTone(10)).toBe("critical");

    render(<HealthBadge score={75} factors={factors} />);
    const badge = screen.getByRole("img", { name: /Deal health 75 of 100/ });
    expect(badge).toHaveTextContent("75");
    fireEvent.mouseEnter(badge);
    expect(screen.getByRole("tooltip")).toHaveTextContent("40 days in Proposal");
  });

  it("renders nothing for closed deals", () => {
    const { container } = render(<HealthBadge score={null} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe("bar charts", () => {
  it("show an empty state when every bar is zero", () => {
    render(<HBarChart data={[{ label: "New", value: 0 }, { label: "Won", value: 0 }]} emptyText="No leads yet." />);
    expect(screen.getByText("No leads yet.")).toBeInTheDocument();
  });
});

describe("ForecastPage", () => {
  it("shows category columns, quota attainment and the per-owner table for managers", async () => {
    signIn("MANAGER");
    vi.spyOn(api, "get").mockResolvedValue({ data: forecast(false) });
    renderPage(<ForecastPage />);
    expect(await screen.findByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.getAllByText("Best case").length).toBeGreaterThan(0);
    expect(screen.getAllByText("50%").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Set quota for Ada Lovelace")).toBeInTheDocument();
  });

  it("shows counts only for Inside Sales and no per-owner table", async () => {
    signIn("INSIDE_SALES");
    vi.spyOn(api, "get").mockResolvedValue({ data: forecast(true) });
    renderPage(<ForecastPage />);
    expect(await screen.findByText(/Deal values are hidden for your role/)).toBeInTheDocument();
    expect(screen.queryByText(/By owner/)).not.toBeInTheDocument();
    expect(screen.queryByText(/₹/)).not.toBeInTheDocument();
  });

  it("offers a retry when the forecast fails to load", async () => {
    signIn("EXECUTIVE");
    vi.spyOn(api, "get").mockRejectedValue(new Error("boom"));
    renderPage(<ForecastPage />);
    expect(await screen.findByText("Couldn't load the forecast")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /try again/i })).toBeInTheDocument();
  });
});
