import { describe, expect, it } from "vitest";
import { allowedActions, normalizeStatus } from "./campaignTypes";

const superAdmin = { id: 1, role: "SUPER_ADMIN" as const };
const vp = { id: 2, role: "VP_SALES" as const };
const manager = { id: 3, role: "MANAGER" as const };

describe("campaign action rules", () => {
  it("lets only SUPER_ADMIN launch straight from draft", () => {
    expect(allowedActions({ status: "draft", createdBy: 2 }, superAdmin)).toContain("launch");
    expect(allowedActions({ status: "draft", createdBy: 2 }, vp)).not.toContain("launch");
    expect(allowedActions({ status: "approved", createdBy: 2 }, vp)).toContain("launch");
  });

  it("never lets a VP review their own campaign, but SUPER_ADMIN may", () => {
    expect(allowedActions({ status: "submitted", createdBy: 2 }, vp)).not.toContain("approve");
    expect(allowedActions({ status: "submitted", createdBy: 2 }, vp)).not.toContain("reject");
    expect(allowedActions({ status: "submitted", createdBy: 9 }, vp)).toEqual(expect.arrayContaining(["approve", "reject"]));
    expect(allowedActions({ status: "submitted", createdBy: 1 }, superAdmin)).toEqual(expect.arrayContaining(["approve", "reject"]));
  });

  it("offers nothing to roles that cannot manage campaigns", () => {
    expect(allowedActions({ status: "submitted", createdBy: 9 }, manager)).toEqual([]);
    expect(allowedActions({ status: "draft", createdBy: 3 }, null)).toEqual([]);
  });

  it("follows the status flow", () => {
    expect(allowedActions({ status: "sending", createdBy: 2 }, vp)).toEqual(["pause", "cancel"]);
    expect(allowedActions({ status: "paused", createdBy: 2 }, vp)).toEqual(["resume", "cancel"]);
    expect(allowedActions({ status: "completed", createdBy: 2 }, vp)).toEqual([]);
    expect(allowedActions({ status: "rejected", createdBy: 2 }, vp)).toContain("submit");
  });

  it("maps legacy status names", () => {
    expect(normalizeStatus("running")).toBe("sending");
    expect(normalizeStatus("pending_approval")).toBe("submitted");
    expect(normalizeStatus("draft")).toBe("draft");
  });
});
