import { describe, expect, test } from "bun:test";
import {
  formatPlayerStatus,
  getOnlinePlayerCount,
} from "../src/utils/botStatus.js";

describe("formatPlayerStatus", () => {
  test("formats the Discord activity text", () => {
    expect(formatPlayerStatus(0)).toBe("0 players connected");
    expect(formatPlayerStatus(1)).toBe("1 player connected");
    expect(formatPlayerStatus(12)).toBe("12 players connected");
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
