import { describe, expect, it } from "vitest";
import { urlBase64ToUint8Array } from "./push";

describe("urlBase64ToUint8Array (VAPID key decoding)", () => {
  it("decodes URL-safe base64 without padding", () => {
    // "hello?" → aGVsbG8_ in URL-safe base64 (no padding)
    expect(Array.from(urlBase64ToUint8Array("aGVsbG8_"))).toEqual([104, 101, 108, 108, 111, 63]);
  });
  it("decodes a 65-byte uncompressed P-256 public key", () => {
    const key = "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U";
    const bytes = urlBase64ToUint8Array(key);
    expect(bytes.length).toBe(65);
    expect(bytes[0]).toBe(0x04);
  });
});
