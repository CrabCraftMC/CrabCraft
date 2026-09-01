import { describe, expect, test } from "bun:test";
import { canonicalMinecraftUuid } from "@crabcraft/shared/analytics";
import { minecraftAnalyticsId } from "@crabcraft/shared/analytics-identity";
import { analyticsPerson } from "../src/utils/analyticsIdentity";

describe("analytics identity", () => {
  test("normalises dashed and undashed UUIDs to one identity", () => {
    const dashed = "123E4567-E89B-12D3-A456-426614174000";
    const undashed = "123e4567e89b12d3a456426614174000";

    expect(canonicalMinecraftUuid(dashed)).toBe(undashed);
    expect(minecraftAnalyticsId(dashed, "test-salt")).toBe(
      minecraftAnalyticsId(undashed, "test-salt"),
    );
  });

  test("matches the cross-runtime HMAC test vector", () => {
    expect(
      minecraftAnalyticsId(
        "123e4567-e89b-12d3-a456-426614174000",
        "test-salt",
      ),
    ).toBe(
      "cc_dbb943fa5348a97b451a8496c14fd88a699eb513165e7b16c3436e6c6bcfdf72",
    );
  });

  test("refuses invalid UUIDs and empty salts", () => {
    expect(minecraftAnalyticsId("Steve", "test-salt")).toBeNull();
    expect(
      minecraftAnalyticsId(
        "123e4567-e89b-12d3-a456-426614174000",
        "  ",
      ),
    ).toBeNull();
  });

  test("exposes linked Minecraft and Discord identity as person properties", () => {
    expect(
      analyticsPerson(
        "123e4567-e89b-12d3-a456-426614174000",
        "test-salt",
        {
          discord_id: "123456789",
          discord_username: "crab.friend",
          minecraft_uuid: "123e4567-e89b-12d3-a456-426614174000",
          minecraft_username: "CrabPlayer",
          nickname: "Crabby",
          role: "member",
        },
      ),
    ).toEqual({
      distinctId:
        "cc_dbb943fa5348a97b451a8496c14fd88a699eb513165e7b16c3436e6c6bcfdf72",
      properties: {
        discord_id: "123456789",
        discord_username: "crab.friend",
        minecraft_uuid: "123e4567-e89b-12d3-a456-426614174000",
        minecraft_nickname: "Crabby",
        minecraft_username: "CrabPlayer",
        name: "CrabPlayer",
        role: "member",
      },
    });
  });
});
