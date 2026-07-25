import { describe, expect, test } from "bun:test";
import { planPunishmentRoleChanges } from "../src/utils/punishmentRoleSyncPlan.js";

const PRIMARY_UUID = "11111111-1111-1111-1111-111111111111";
const ALT_UUID = "22222222-2222-2222-2222-222222222222";
const WARNING_ONLY_UUID = "33333333-3333-3333-3333-333333333333";

describe("planPunishmentRoleChanges", () => {
  test("primary ban adds role", () => {
    const plan = planPunishmentRoleChanges(
      [{ discordId: "user-1", minecraftUuid: PRIMARY_UUID }],
      [PRIMARY_UUID],
      [],
    );

    expect(plan).toEqual({ add: ["user-1"], remove: [] });
  });

  test("alt mute adds role", () => {
    const plan = planPunishmentRoleChanges(
      [
        { discordId: "user-1", minecraftUuid: PRIMARY_UUID },
        { discordId: "user-1", minecraftUuid: ALT_UUID },
      ],
      [ALT_UUID.replaceAll("-", "")],
      [],
    );

    expect(plan).toEqual({ add: ["user-1"], remove: [] });
  });

  test("inactive or absent punishment removes role", () => {
    const plan = planPunishmentRoleChanges(
      [{ discordId: "user-1", minecraftUuid: PRIMARY_UUID }],
      [],
      ["user-1"],
    );

    expect(plan).toEqual({ add: [], remove: ["user-1"] });
  });

  test("warning and kick only results do not qualify", () => {
    const plan = planPunishmentRoleChanges(
      [{ discordId: "user-1", minecraftUuid: WARNING_ONLY_UUID }],
      [],
      ["user-1"],
    );

    expect(plan).toEqual({ add: [], remove: ["user-1"] });
  });

  test("failed API result produces no role mutations", () => {
    const plan = planPunishmentRoleChanges(
      [{ discordId: "user-1", minecraftUuid: PRIMARY_UUID }],
      null,
      ["user-2"],
    );

    expect(plan).toEqual({ add: [], remove: [] });
  });
});
