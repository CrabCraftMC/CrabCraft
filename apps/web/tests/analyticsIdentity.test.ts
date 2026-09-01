import { describe, expect, test } from "bun:test";
import { buildWebAnalyticsIdentity } from "../src/lib/analyticsIdentity";

describe("web analytics identity", () => {
  test("sets linked Minecraft and Discord person properties", () => {
    expect(
      buildWebAnalyticsIdentity("cc_person", {
        discordId: "123456789",
        discordUsername: "crab.friend",
        minecraftUuid: "123E4567-E89B-12D3-A456-426614174000",
        minecraftUsername: "CrabPlayer",
        minecraftNickname: "Crabby",
        role: "verified",
      }),
    ).toEqual({
      distinctId: "cc_person",
      properties: {
        discord_id: "123456789",
        discord_username: "crab.friend",
        minecraft_uuid: "123e4567-e89b-12d3-a456-426614174000",
        minecraft_username: "CrabPlayer",
        minecraft_nickname: "Crabby",
        name: "CrabPlayer",
        role: "verified",
      },
    });
  });
});
