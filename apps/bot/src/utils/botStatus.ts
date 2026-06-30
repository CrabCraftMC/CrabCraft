import { ActivityType, type Client } from "discord.js";
import { BOT_STATUS_REFRESH_MS } from "./constants.js";
import logger from "./logger.js";

const FETCH_TIMEOUT_MS = 5000;

interface PlayersResponse {
  count?: unknown;
  players?: unknown;
}

export function formatPlayerStatus(playerCount: number): string {
  const noun = playerCount === 1 ? "other" : "others";
  return `with ${playerCount} ${noun} connected`;
}

export function getOnlinePlayerCount(data: PlayersResponse): number | null {
  if (typeof data.count === "number" && Number.isInteger(data.count) && data.count >= 0) {
    return data.count;
  }

  if (Array.isArray(data.players)) {
    return data.players.length;
  }

  return null;
}

export async function fetchOnlinePlayerCount(apiUrl: string): Promise<number | null> {
  try {
    const ctrl = new AbortController();
    const timeout = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS);
    const res = await fetch(`${apiUrl}/players`, { signal: ctrl.signal }).finally(() =>
      clearTimeout(timeout),
    );
    if (!res.ok) return null;

    return getOnlinePlayerCount((await res.json()) as PlayersResponse);
  } catch {
    return null;
  }
}

export function startBotPlayerStatus(
  client: Client,
  apiUrl: string,
  refreshMs = BOT_STATUS_REFRESH_MS,
) {
  let currentActivity: string | null = null;

  const update = async () => {
    const playerCount = await fetchOnlinePlayerCount(apiUrl);
    if (playerCount === null) {
      logger.warn("Could not refresh Discord bot player-count status.");
      return;
    }

    const activity = formatPlayerStatus(playerCount);
    if (activity === currentActivity) return;

    client.user?.setActivity(activity, { type: ActivityType.Playing });
    currentActivity = activity;
  };

  void update();
  setInterval(() => void update(), refreshMs);
}
