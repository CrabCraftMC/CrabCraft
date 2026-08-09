import { describe, expect, test } from "bun:test";
import { playerDisplayName } from "../src/lib/playerName";

describe("playerDisplayName", () => {
  test("prefers a non-empty nickname", () => {
    expect(playerDisplayName("Crabby", "RealUsername")).toBe("Crabby");
  });

  test("falls back to the username for missing or blank nicknames", () => {
    expect(playerDisplayName(null, "RealUsername")).toBe("RealUsername");
    expect(playerDisplayName("   ", "RealUsername")).toBe("RealUsername");
  });

  test("uses the supplied fallback when neither name is available", () => {
    expect(playerDisplayName(null, null, "Player")).toBe("Player");
  });
});
