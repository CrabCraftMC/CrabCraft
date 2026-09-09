import { expect, mock, test } from "bun:test";
import { randomUUID } from "node:crypto";

// Isolate module mocks from other command tests, which mock the same bot modules.
if (process.env.CRABCRAFT_TEST_LEADERBOARD_CHILD !== "1") {
  test("moderators can reversibly exclude players without exposing the response", () => {
    const result = Bun.spawnSync([process.execPath, import.meta.path], {
      env: { ...process.env, CRABCRAFT_TEST_LEADERBOARD_CHILD: "1" },
    });
    expect(result.stderr.toString()).toBe("");
    expect(result.exitCode).toBe(0);
  });
} else {
  const modRole = randomUUID();
  const targetId = randomUUID();
  const setPlayerAwardsExcluded = mock(async (_id: string, _excluded: boolean) => true);
  mock.module("../src/utils/appDb.js", () => ({ setPlayerAwardsExcluded }));
  mock.module("../src/utils/config.js", () => ({ default: { MOD_ROLE_ID: modRole } }));
  mock.module("../src/utils/logger.js", () => ({ default: { error: mock() } }));
  mock.module("../src/utils/leaderboard.js", () => ({
    fetchLeaderboardData: mock(), buildLeaderboardComponents: mock(), DEFAULT_LEADERBOARD_SEASON: 1,
  }));
  mock.module("../src/utils/leaderboardState.js", () => ({
    saveLeaderboardState: mock(), loadLeaderboardState: mock(),
  }));
  mock.module("../src/utils/playerEmoji.js", () => ({ syncLeaderboardEmojis: mock() }));
  const { MessageFlags } = await import("discord.js");
  const { default: LeaderboardCommand } = await import("../src/commands/Admin/leaderboard.js");
  const command = new LeaderboardCommand();

  function interaction(subcommand: string, moderator: boolean) {
    return {
      guild: { members: { fetch: mock(async () => ({ roles: { cache: new Set(moderator ? [modRole] : []) } })) } },
      user: { id: randomUUID() },
      options: { getSubcommand: () => subcommand, getUser: () => ({ id: targetId }) },
      reply: mock(async (_response: unknown) => {}),
      deferReply: mock(async (_response: unknown) => {}),
      editReply: mock(async (_response: unknown) => {}),
    };
  }

  for (const subcommand of ["exclude", "include"]) {
    const denied = interaction(subcommand, false);
    await command.execute(denied as never);
    expect(setPlayerAwardsExcluded).not.toHaveBeenCalled();
    expect(denied.reply).toHaveBeenCalledWith(expect.objectContaining({
      flags: MessageFlags.IsComponentsV2 | MessageFlags.Ephemeral,
    }));
  }

  for (const excluded of [true, false]) {
    const allowed = interaction(excluded ? "exclude" : "include", true);
    await command.execute(allowed as never);
    expect(setPlayerAwardsExcluded).toHaveBeenLastCalledWith(targetId, excluded);
    expect(allowed.deferReply).toHaveBeenCalledWith({ flags: MessageFlags.Ephemeral });
    expect(allowed.editReply).toHaveBeenCalledWith(expect.objectContaining({ allowedMentions: { parse: [] } }));
    expect(allowed.reply).not.toHaveBeenCalled();
  }

  setPlayerAwardsExcluded.mockResolvedValueOnce(false);
  const missing = interaction("exclude", true);
  await command.execute(missing as never);
  expect(JSON.stringify(missing.editReply.mock.calls)).toContain("no registered player record");

  setPlayerAwardsExcluded.mockRejectedValueOnce(new Error("Synthetic database failure"));
  const failed = interaction("exclude", true);
  await command.execute(failed as never);
  expect(failed.deferReply).toHaveBeenCalledWith({ flags: MessageFlags.Ephemeral });
  expect(JSON.stringify(failed.editReply.mock.calls)).toContain("Could not update award visibility");

  const definition = await command.build();
  for (const subcommand of ["exclude", "include"]) {
    const option = definition.options?.find((value) => value.name === subcommand);
    expect(option).toBeDefined();
    expect((option as { options: Array<{ name: string; required: boolean }> }).options)
      .toEqual([expect.objectContaining({ name: "user", required: true })]);
  }
}
