import { afterEach, describe, expect, test } from "bun:test";
import { trackUmamiEvent, trackUmamiEventOnce } from "../src/lib/umami";

afterEach(() => {
  Reflect.deleteProperty(globalThis, "window");
});

describe("Umami event tracking", () => {
  test("does nothing when the tracker is unavailable", () => {
    expect(trackUmamiEvent("test-event")).toBe(false);
  });

  test("sends event data to Umami", () => {
    const events: unknown[][] = [];
    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: {
        umami: {
          track: (...args: unknown[]) => events.push(args),
        },
      },
    });

    expect(trackUmamiEvent("tool-used", { tool: "circle-generator" })).toBe(
      true,
    );
    expect(events).toEqual([
      ["tool-used", { tool: "circle-generator" }],
    ]);
  });

  test("sends once-only events once per key", () => {
    const events: unknown[][] = [];
    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: {
        umami: {
          track: (...args: unknown[]) => events.push(args),
        },
      },
    });

    const key = `test-${crypto.randomUUID()}`;
    trackUmamiEventOnce(key, "tool-used", { tool: "portal-calculator" });
    trackUmamiEventOnce(key, "tool-used", { tool: "portal-calculator" });

    expect(events).toHaveLength(1);
  });
});
