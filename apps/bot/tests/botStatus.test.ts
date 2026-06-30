import { describe, expect, test } from "bun:test";
import {
  formatPlayerStatus,
  getOnlinePlayerCount,
} from "../src/utils/botStatus.js";

describe("formatPlayerStatus", () => {
  test("formats the Discord activity text", () => {
    expect(formatPlayerStatus(0)).toBe("with 0 others connected");
    expect(formatPlayerStatus(1)).toBe("with 1 other connected");
    expect(formatPlayerStatus(12)).toBe("with 12 others connected");
  });
});

describe("getOnlinePlayerCount", () => {
  test("uses the API count when present", () => {
    expect(getOnlinePlayerCount({ count: 3, players: [] })).toBe(3);
  });

  test("falls back to the players array length", () => {
    expect(getOnlinePlayerCount({ players: [{}, {}] })).toBe(2);
  });

  test("rejects invalid payloads", () => {
    expect(getOnlinePlayerCount({ count: -1 })).toBeNull();
    expect(getOnlinePlayerCount({})).toBeNull();
  });
});
