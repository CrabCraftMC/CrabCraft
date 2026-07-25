import type { StreamChannelPollCandidate } from "./appDb.js";

export const MAX_TIKTOK_POLL_TARGETS = 25;
export const TIKTOK_POLL_CONCURRENCY = 5;
export const TIKTOK_REQUEST_TIMEOUT_MS = 10_000;

const TIKTOK_USERNAME_PATTERN = /^@?[A-Za-z0-9._]{2,24}$/;

export function selectTikTokPollTargets(
  candidates: ReadonlyArray<StreamChannelPollCandidate>,
  guildMemberIds: ReadonlySet<string>,
): StreamChannelPollCandidate[] {
  return candidates
    .filter(
      (candidate) =>
        candidate.player_role !== "unverified" &&
        guildMemberIds.has(candidate.discord_user_id) &&
        TIKTOK_USERNAME_PATTERN.test(candidate.channel_id),
    )
    .slice(0, MAX_TIKTOK_POLL_TARGETS);
}

export async function checkTikTokPollTargets(
  targets: ReadonlyArray<StreamChannelPollCandidate>,
  check: (username: string) => Promise<boolean>,
): Promise<Array<{ channel: StreamChannelPollCandidate; isLive: boolean }>> {
  const results = new Array<{
    channel: StreamChannelPollCandidate;
    isLive: boolean;
  }>(targets.length);
  let nextIndex = 0;

  async function worker(): Promise<void> {
    while (nextIndex < targets.length) {
      const index = nextIndex++;
      const channel = targets[index];
      results[index] = {
        channel,
        isLive: await check(channel.channel_id),
      };
    }
  }

  const workerCount = Math.min(TIKTOK_POLL_CONCURRENCY, targets.length);
  await Promise.all(Array.from({ length: workerCount }, () => worker()));
  return results;
}
