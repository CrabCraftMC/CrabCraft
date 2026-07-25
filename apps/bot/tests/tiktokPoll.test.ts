import { describe, expect, test } from "bun:test";
import type { StreamChannelPollCandidate } from "../src/utils/appDb.js";
import {
  checkTikTokPollTargets,
  MAX_TIKTOK_POLL_TARGETS,
  selectTikTokPollTargets,
  TIKTOK_POLL_CONCURRENCY,
} from "../src/utils/tiktokPoll.js";
import { singleFlight, withDeadline } from "../src/utils/streamPoll.js";

function candidate(
  id: number,
  discordUserId: string,
  playerRole: StreamChannelPollCandidate["player_role"],
  channelId = `creator_${id}`,
): StreamChannelPollCandidate {
  return {
    id,
    platform: "tiktok",
    channel_id: channelId,
    discord_user_id: discordUserId,
    display_name: null,
    player_role: playerRole,
  };
}

describe("TikTok poll admission", () => {
  test("unverified, departed, and malformed rows never reach the connector", async () => {
    const channels = [
      candidate(1, "verified-member", "verified", "@verified.creator"),
      candidate(2, "oauth-only", "unverified"),
      candidate(3, "departed", "verified"),
      candidate(4, "malformed", "verified", "https://example.test/live"),
    ];
    const checked: string[] = [];
    const admitted = selectTikTokPollTargets(
      channels,
      new Set(["verified-member", "oauth-only", "malformed"]),
    );

    await checkTikTokPollTargets(admitted, async (username) => {
      checked.push(username);
      return false;
    });

    expect(admitted).toEqual([channels[0]]);
    expect(checked).toEqual(["@verified.creator"]);
  });

  test("caps connector work while retaining verified guild members", () => {
    const channels = Array.from(
      { length: MAX_TIKTOK_POLL_TARGETS + 10 },
      (_, index) => candidate(index, `member-${index}`, "verified"),
    );
    const memberIds = new Set(channels.map((channel) => channel.discord_user_id));

    expect(selectTikTokPollTargets(channels, memberIds)).toHaveLength(
      MAX_TIKTOK_POLL_TARGETS,
    );
  });
});

test("TikTok checks never exceed the connector concurrency limit", async () => {
  const channels = Array.from(
    { length: MAX_TIKTOK_POLL_TARGETS },
    (_, index) => candidate(index, `member-${index}`, "verified"),
  );
  let active = 0;
  let peak = 0;

  const results = await checkTikTokPollTargets(channels, async () => {
    active++;
    peak = Math.max(peak, active);
    await Promise.resolve();
    active--;
    return true;
  });

  expect(results).toHaveLength(MAX_TIKTOK_POLL_TARGETS);
  expect(peak).toBe(TIKTOK_POLL_CONCURRENCY);
});

test("single-flight rejects overlap and permits the next poll", async () => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let calls = 0;
  const run = singleFlight(async () => {
    calls++;
    await gate;
  });

  const first = run();
  expect(await run()).toBe(false);
  release();
  expect(await first).toBe(true);
  expect(await run()).toBe(true);
  expect(calls).toBe(2);
});

test("TikTok checks have an application-level deadline", async () => {
  const never = new Promise<boolean>(() => {});

  await expect(withDeadline(never, 1)).rejects.toThrow(
    "Stream check timed out",
  );
});
