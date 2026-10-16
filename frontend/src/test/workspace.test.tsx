import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { blockText, editorType, moveItem, splitLinks } from "@/features/recordpages/blocks";
import { LinkifiedText } from "@/features/recordpages/LinkifiedText";
import { connectorLine, edgePoint } from "@/features/whiteboard/geometry";
import { cellValue, changedProps, toValuePayload, type DbProperty, type DbRecordEnvelope } from "@/features/databases/propertyTypes";
import { fromLocalInput, toLocalInput } from "@/features/generic/datetime";

describe("record page blocks", () => {
  it("reads JSON content and falls back to raw text", () => {
    expect(blockText('{"text":"Hello"}')).toBe("Hello");
    expect(blockText("plain legacy text")).toBe("plain legacy text");
    expect(blockText(null)).toBe("");
    expect(editorType("checklist")).toBe("todo");
    expect(editorType("heading_3")).toBe("heading_3");
  });
  it("moves blocks", () => {
    expect(moveItem([1, 2, 3], 0, 2)).toEqual([2, 3, 1]);
    expect(moveItem([1, 2, 3], 2, 0)).toEqual([3, 1, 2]);
  });
  it("splits links without swallowing punctuation", () => {
    expect(splitLinks("see https://acme.com/x. ok")).toEqual([
      { kind: "text", value: "see " },
      { kind: "link", value: "https://acme.com/x" },
      { kind: "text", value: ". ok" },
    ]);
  });
  it("only ever links safe schemes and never renders HTML", () => {
    const { container } = render(<LinkifiedText text={'<img src=x onerror=alert(1)> javascript:alert(1) https://ok.example'} />);
    expect(container.querySelector("img")).toBeNull();
    const links = container.querySelectorAll("a");
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute("href")).toBe("https://ok.example/");
  });
});

describe("whiteboard connector geometry", () => {
  it("runs edge to edge and follows the boxes", () => {
    const a = { x: 0, y: 0, w: 100, h: 100 };
    const b = { x: 300, y: 0, w: 100, h: 100 };
    expect(connectorLine(a, b)).toMatchObject({ x1: 100, y1: 50, x2: 300, y2: 50 });
    expect(connectorLine({ ...a, y: 200 }, { ...b, y: 200 }).y1).toBe(250);
    expect(edgePoint(a, 50, 500)).toEqual({ x: 50, y: 100 });
  });
});

describe("database cells", () => {
  const env: DbRecordEnvelope = {
    record: { id: 1, databaseId: 1, ownerId: 1, createdBy: 1, createdAt: "", updatedAt: "" },
    values: [
      { id: 1, recordId: 1, propertyId: 10, textValue: null, numberValue: null, dateValue: "2030-01-15T00:00:00Z", boolValue: null, jsonValue: null },
      { id: 2, recordId: 1, propertyId: 11, textValue: null, numberValue: null, dateValue: null, boolValue: null, jsonValue: "[4,5]" },
    ],
    formulas: { "12": { value: 120, error: null } },
  };
  const prop = (id: number, type: DbProperty["type"]): DbProperty => ({
    id,
    databaseId: 1,
    name: `p${id}`,
    type,
    optionsJson: null,
    relationDbId: null,
    required: false,
    position: 0,
  });
  it("reads typed values", () => {
    expect(cellValue(env, prop(10, "date"))).toBe("2030-01-15");
    expect(cellValue(env, prop(11, "relation"))).toEqual([4, 5]);
    expect(cellValue(env, prop(12, "formula"))).toEqual({ value: 120, error: null });
    expect(cellValue(env, prop(99, "checkbox"))).toBe(false);
  });
  it("builds payloads and diffs only changed values", () => {
    expect(toValuePayload(11, "relation", [4])).toEqual({ propertyId: 11, jsonValue: [4] });
    expect(toValuePayload(9, "url", "")).toEqual({ propertyId: 9, textValue: null });
    expect(changedProps({ 1: "a", 2: [1] }, { 1: "a", 2: [1, 2] })).toEqual([2]);
  });
  it("round-trips datetime-local values", () => {
    const iso = fromLocalInput("2030-05-01T10:30");
    expect(iso).not.toBeNull();
    expect(toLocalInput(iso)).toBe("2030-05-01T10:30");
    expect(fromLocalInput("")).toBeNull();
  });
});

vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      get: vi.fn(async () => ({
        data: {
          page: { id: 1, relatedType: "Company", relatedId: 7, icon: null, coverColor: null },
          canEdit: false,
          blocks: [
            { id: 1, pageId: 1, blockType: "heading_1", content: '{"text":"Plan"}', position: 0, checked: false },
            { id: 2, pageId: 1, blockType: "todo", content: '{"text":"Call back"}', position: 1, checked: true },
            { id: 3, pageId: 1, blockType: "divider", content: null, position: 2, checked: false },
          ],
        },
      })),
    },
  };
});

describe("RecordPagePanel", () => {
  it("renders blocks read-only when the caller cannot edit", async () => {
    const { RecordPagePanel } = await import("@/features/recordpages/RecordPagePanel");
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RecordPagePanel relatedType="Company" relatedId={7} />
      </QueryClientProvider>
    );
    expect(await screen.findByText("Plan")).toBeInTheDocument();
    expect(screen.getByText("Call back")).toBeInTheDocument();
    expect(screen.getByRole("checkbox")).toBeDisabled();
    expect(screen.queryByRole("button", { name: /delete block/i })).toBeNull();
    expect(screen.queryByLabelText(/block type/i)).toBeNull();
  });
});
