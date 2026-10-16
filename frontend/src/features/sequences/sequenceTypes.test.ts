import { describe, expect, it } from "vitest";
import { blankStep, cumulativeHours, dayLabel, describeDelay, toPayload, validateStep } from "./sequenceTypes";

describe("sequence helpers", () => {
  it("describes delays and cumulative schedule", () => {
    expect(describeDelay(0, 0)).toBe("Immediately");
    expect(describeDelay(1, 0)).toBe("After 1 day");
    expect(describeDelay(2, 4)).toBe("After 2 days 4 h");
    const offs = cumulativeHours([
      { delayDays: 0, delayHours: 0 },
      { delayDays: 2, delayHours: 0 },
      { delayDays: 0, delayHours: 5 },
    ]);
    expect(offs).toEqual([0, 48, 53]);
    expect(dayLabel(0)).toBe("Day 1");
    expect(dayLabel(53)).toBe("Day 3 · +5h");
  });

  it("validates steps like the server", () => {
    expect(validateStep({ ...blankStep("EMAIL"), subject: "", body: "x" })).toMatch(/subject/);
    expect(validateStep({ ...blankStep("EMAIL"), subject: "Hi", body: "x" })).toBeNull();
    expect(validateStep({ ...blankStep("TASK"), subject: "" })).toMatch(/title/);
    expect(validateStep({ ...blankStep("WHATSAPP"), waTemplateName: "bad name" })).toMatch(/template/);
    expect(validateStep({ ...blankStep("WHATSAPP"), waTemplateName: "follow_up" })).toBeNull();
    expect(validateStep({ ...blankStep("TASK"), subject: "t", delayDays: 400 })).toMatch(/Delay/);
  });

  it("builds a channel-specific payload", () => {
    const p = toPayload({
      name: "  Follow-up ",
      description: "",
      active: true,
      steps: [
        { ...blankStep("EMAIL"), subject: " Hi ", body: "Body" },
        { ...blankStep("WHATSAPP"), waTemplateName: "follow_up", waTemplateParams: ["{{record.firstName}}", " ", ""] },
      ],
    });
    expect(p.name).toBe("Follow-up");
    expect(p.description).toBeNull();
    expect(p.steps[0]).toEqual({ channel: "EMAIL", delayDays: 0, delayHours: 0, subject: "Hi", body: "Body" });
    expect(p.steps[1]).toMatchObject({ channel: "WHATSAPP", waTemplateName: "follow_up", waTemplateParams: ["{{record.firstName}}"] });
    expect(p.steps[1]).not.toHaveProperty("subject");
  });
});
