import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeAll, describe, expect, it, vi } from "vitest";
import { ChatPanel } from "./ChatPanel";
import { normalizePhone } from "./whatsappTypes";

const PHONE = "+919876543210";

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return {
    ...actual,
    api: {
      get: vi.fn(async (url: string) => {
        if (url.startsWith("/whatsapp/consent/"))
          return { data: { phone: PHONE, status: "opted_out", optedOut: true, source: "inbound_keyword", reason: "Replied STOP", updatedAt: null, history: [] } };
        return {
          data: [
            { id: 3, accountId: 1, direction: "outbound", type: "text", body: "See you then", status: "read", fromNumber: null, toNumber: PHONE, createdAt: "2026-10-15T10:02:00Z" },
            { id: 2, accountId: 1, direction: "inbound", type: "text", body: null, bodyHidden: true, status: "received", fromNumber: "919876543210", toNumber: null, createdAt: "2026-10-15T10:01:00Z" },
            { id: 1, accountId: 1, direction: "outbound", type: "text", body: "Hello!", status: "failed", errorMessage: "Not on WhatsApp", fromNumber: null, toNumber: PHONE, createdAt: "2026-10-15T10:00:00Z" },
          ],
        };
      }),
      post: vi.fn(),
    },
  };
});

beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ChatPanel phone={PHONE} accounts={[{ id: 1, displayPhoneNumber: "+15550001111", active: true }]} canSend />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe("WhatsApp chat panel", () => {
  it("shows bubbles with delivery ticks, hidden bodies and the consent state", async () => {
    renderPanel();
    expect(await screen.findByText("See you then")).toBeInTheDocument();
    expect(screen.getByLabelText("Read")).toBeInTheDocument();
    expect(screen.getByLabelText("Failed")).toBeInTheDocument();
    expect(screen.getByText(/Message hidden/)).toBeInTheDocument();
    expect(await screen.findByText("Opted out")).toBeInTheDocument();
    // Opted-out numbers can't be messaged: the composer is replaced by an explanation.
    expect(screen.getByText(/This number opted out/)).toBeInTheDocument();
    expect(screen.queryByPlaceholderText("Type a message")).not.toBeInTheDocument();
  });
});

describe("normalizePhone", () => {
  it("matches the server's E.164 normalisation", () => {
    expect(normalizePhone("+91 98765-43210")).toBe(PHONE);
    expect(normalizePhone("0091 9876543210")).toBe(PHONE);
    expect(normalizePhone("919876543210")).toBe(PHONE);
    expect(normalizePhone("12")).toBeNull();
  });
});
