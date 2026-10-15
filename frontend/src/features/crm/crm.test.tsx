import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DataTable, sortParam } from "@/components/ui/DataTable";
import { buildEntries } from "@/features/activities/ActivitiesPage";
import type { Activity } from "@/lib/types";
import { ageLabel, changedFields } from "./ListParts";

const act = (over: Partial<Activity>): Activity => ({
  id: 1,
  organizationId: 1,
  type: "call",
  subject: null,
  body: null,
  userId: 1,
  relatedType: null,
  relatedId: null,
  outcome: null,
  scheduledAt: null,
  completedAt: null,
  nextFollowUpAt: null,
  correctionForId: null,
  durationMinutes: null,
  createdAt: "2026-10-01T10:00:00Z",
  ...over,
});

describe("changedFields", () => {
  it("keeps only what the user actually changed", () => {
    const original = { id: 1, name: "Acme", stage: "New", value: 10, notes: null as string | null };
    expect(changedFields(original, { ...original, stage: "Contacted", notes: "hi" })).toEqual({ stage: "Contacted", notes: "hi" });
    expect(changedFields(original, { ...original })).toEqual({});
  });
});

describe("ageLabel", () => {
  const now = Date.parse("2026-10-15T12:00:00Z");
  it("formats compact ages", () => {
    expect(ageLabel("2026-10-15T11:59:30Z", now)).toBe("just now");
    expect(ageLabel("2026-10-15T09:00:00Z", now)).toBe("3h");
    expect(ageLabel("2026-10-10T12:00:00Z", now)).toBe("5d");
    expect(ageLabel(null, now)).toBe("");
  });
});

describe("buildEntries (append-only timeline)", () => {
  it("shows the newest correction with the original kept underneath", () => {
    const original = act({ id: 1, subject: "Spoke to CEO" });
    const correction = act({ id: 2, subject: "Spoke to CFO", correctionForId: 1, createdAt: "2026-10-02T10:00:00Z" });
    const entries = buildEntries({
      count: 2,
      data: [correction, original],
      corrections: { "1": [correction] },
      originals: { "1": original },
    });
    expect(entries).toHaveLength(1);
    expect(entries[0].shown.subject).toBe("Spoke to CFO");
    expect(entries[0].original?.subject).toBe("Spoke to CEO");
  });

  it("leaves uncorrected entries alone", () => {
    const entries = buildEntries({ count: 1, data: [act({ id: 7 })] });
    expect(entries[0].shown.id).toBe(7);
    expect(entries[0].original).toBeNull();
  });
});

describe("DataTable server mode", () => {
  it("asks the server to sort and to page", async () => {
    const onSort = vi.fn();
    const onPage = vi.fn();
    render(
      <DataTable
        columns={[{ key: "name", header: "Name", sortKey: "name", render: (r: { id: number; name: string }) => r.name }]}
        rows={[{ id: 1, name: "Alpha" }]}
        sort={null}
        onSortChange={onSort}
        pagination={{ page: 0, pageSize: 1, total: 3, onPageChange: onPage }}
      />
    );
    await userEvent.click(screen.getByText("Name"));
    expect(onSort).toHaveBeenCalledWith({ key: "name", dir: "asc" });
    expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /next page/i }));
    expect(onPage).toHaveBeenCalledWith(1);
    expect(sortParam({ key: "value", dir: "desc" })).toBe("value,desc");
  });
});
