import type { StreamChannelPollCandidate } from "./appDb.js";

export const MAX_TWITCH_POLL_TARGETS = 100;
export const TWITCH_REQUEST_TIMEOUT_MS = 10_000;

const TWITCH_LOGIN_PATTERN = /^[A-Za-z0-9_]{4,25}$/;

export function selectTwitchPollTargets(
  candidates: ReadonlyArray<StreamChannelPollCandidate>,
  guildMemberIds: ReadonlySet<string>,
): StreamChannelPollCandidate[] {
  const selected = new Map<string, StreamChannelPollCandidate>();

  for (const candidate of candidates) {
    const login = candidate.channel_id.toLowerCase();
    if (
      candidate.player_role === "unverified" ||
      !guildMemberIds.has(candidate.discord_user_id) ||
      !TWITCH_LOGIN_PATTERN.test(candidate.channel_id) ||
      selected.has(login)
    ) {
      continue;
    }

    selected.set(login, candidate);
    if (selected.size === MAX_TWITCH_POLL_TARGETS) break;
  }

  return [...selected.values()];
}

export function twitchLoginBatches(
  targets: ReadonlyArray<StreamChannelPollCandidate>,
): string[][] {
  const logins = targets.map((target) => target.channel_id.toLowerCase());
  return Array.from(
    { length: Math.ceil(logins.length / MAX_TWITCH_POLL_TARGETS) },
    (_, index) =>
      logins.slice(
        index * MAX_TWITCH_POLL_TARGETS,
        (index + 1) * MAX_TWITCH_POLL_TARGETS,
      ),
  );
}
