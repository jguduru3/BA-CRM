import { describe, expect, it } from "vitest";
import { formatDuration, looksLikeCron, parseJsonObject, serializeConfig, serializeTrigger } from "./automationConfig";

describe("automation builder helpers", () => {
  it("serialises only filled-in config fields", () => {
    expect(JSON.parse(serializeConfig({ promptTemplate: "Hi {{lead.name}}", systemPrompt: "  ", maxTokens: 500, to: undefined }))).toEqual({
      promptTemplate: "Hi {{lead.name}}",
      maxTokens: 500,
    });
  });

  it("parses config JSON defensively", () => {
    expect(parseJsonObject('{"url":"https://x"}')).toEqual({ url: "https://x" });
    expect(parseJsonObject("[1,2]")).toEqual({});
    expect(parseJsonObject("{broken")).toEqual({});
    expect(parseJsonObject(null)).toEqual({});
  });

  it("builds the trigger config only for schedules", () => {
    expect(serializeTrigger("manual", { cron: "0 9 * * *" })).toBe("");
    expect(JSON.parse(serializeTrigger("scheduled", { cron: " 0 9 * * MON-FRI ", timezone: "" }))).toEqual({
      cron: "0 9 * * MON-FRI",
      timezone: "UTC",
    });
  });

  it("recognises 5- and 6-field cron expressions", () => {
    expect(looksLikeCron("0 9 * * MON-FRI")).toBe(true);
    expect(looksLikeCron("0 0 9 * * *")).toBe(true);
    expect(looksLikeCron("every day")).toBe(false);
  });

  it("formats run durations", () => {
    expect(formatDuration(null)).toBe("—");
    expect(formatDuration(420)).toBe("420 ms");
    expect(formatDuration(2500)).toBe("2.5 s");
    expect(formatDuration(125_000)).toBe("2m 5s");
  });
});
