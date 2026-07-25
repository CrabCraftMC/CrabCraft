import { describe, expect, test } from "bun:test";
import type { StreamChannelPollCandidate } from "../src/utils/appDb.js";
import {
  MAX_TWITCH_POLL_TARGETS,
  selectTwitchPollTargets,
  twitchLoginBatches,
} from "../src/utils/twitchPoll.js";

function candidate(
  id: number,
  discordUserId: string,
  playerRole: StreamChannelPollCandidate["player_role"],
  channelId = `creator_${id}`,
): StreamChannelPollCandidate {
  return {
    id,
    platform: "twitch",
    channel_id: channelId,
    discord_user_id: discordUserId,
    display_name: null,
    player_role: playerRole,
  };
}

describe("Twitch poll admission", () => {
  test("unverified, departed, malformed, and duplicate rows never reach Helix", () => {
    const channels = [
      candidate(1, "verified-member", "verified", "Real_Creator"),
      candidate(2, "oauth-only", "unverified", "oauth_creator"),
      candidate(3, "departed", "verified", "departed_creator"),
      candidate(4, "malformed", "verified", "not-a-login"),
      candidate(5, "duplicate", "verified", "real_creator"),
    ];

    const admitted = selectTwitchPollTargets(
      channels,
      new Set([
        "verified-member",
        "oauth-only",
        "malformed",
        "duplicate",
      ]),
    );

    expect(admitted).toEqual([channels[0]]);
    expect(twitchLoginBatches(admitted)).toEqual([["real_creator"]]);
  });

  test("caps each cycle to one Helix request of 100 verified guild members", () => {
    const channels = Array.from(
      { length: MAX_TWITCH_POLL_TARGETS + 20 },
      (_, index) => candidate(index, `member-${index}`, "verified"),
    );
    const memberIds = new Set(channels.map((channel) => channel.discord_user_id));
    const admitted = selectTwitchPollTargets(channels, memberIds);
    const batches = twitchLoginBatches(admitted);

    expect(admitted).toHaveLength(MAX_TWITCH_POLL_TARGETS);
    expect(batches).toHaveLength(1);
    expect(batches[0]).toHaveLength(MAX_TWITCH_POLL_TARGETS);
  });

  test("retains moderator-managed verified Twitch logins", () => {
    const channel = candidate(1, "member", "moderator", "valid_login");

    expect(selectTwitchPollTargets([channel], new Set(["member"]))).toEqual([
      channel,
    ]);
  });
});
