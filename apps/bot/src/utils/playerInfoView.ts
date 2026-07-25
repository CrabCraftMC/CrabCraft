import {
  ActionRowBuilder,
  AttachmentBuilder,
  StringSelectMenuBuilder,
  StringSelectMenuOptionBuilder,
} from "discord.js";
import { generatePlayerCard, type PlayerCardStats } from "./playerCard.js";
import logger from "./logger.js";

const API = "https://api.crabcraft.net";

/** customId prefix for the season-switch dropdown: `playerinfo:season:<uuid>`. */
export const SEASON_SELECT_PREFIX = "playerinfo:season:";

export interface ResolvedTarget {
  uuid: string;
  username: string | null;
  discordUsername: string | null;
}

export interface Season {
  id: string;
  name: string;
}

interface CrownResponse {
  crown?: { rank: number; gold: number; silver: number; bronze: number; crown_score: number } | null;
  username?: string | null;
}
interface StatsResponse {
  username?: string | null;
  stats?: PlayerCardStats | null;
}
interface SeasonsResponse {
  seasons?: Season[];
}

interface FetchResult<T> {
  ok: boolean;
  data: T | null;
  threw: boolean;
}

async function fetchJson<T>(url: string): Promise<FetchResult<T>> {
  try {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 5000);
    const res = await fetch(url, { signal: ctrl.signal }).finally(() => clearTimeout(t));
    if (!res.ok) return { ok: false, data: null, threw: false };
    return { ok: true, data: (await res.json()) as T, threw: false };
  } catch (err) {
    logger.warn(`playerinfo: fetch failed for ${url}: ${(err as Error).message}`);
    return { ok: false, data: null, threw: true };
  }
}

/** Seasons the player has stat data for, newest first. Empty on error/none. */
export async function fetchPlayerSeasons(uuid: string): Promise<Season[]> {
  const res = await fetchJson<SeasonsResponse>(`${API}/players/${uuid}/seasons`);
  return res.data?.seasons ?? [];
}

export type ViewReply = {
  components: ActionRowBuilder<StringSelectMenuBuilder>[];
  files: AttachmentBuilder[];
};

/**
 * Build the /playerinfo message for a given season: fetches that season's stats
 * + crown, renders the card, and appends a season-switch dropdown when the
 * player has data in more than one season. Returns `{ error }` only when the API
 * is unreachable (a 404 for a season is a valid empty-card state).
 */
export async function buildPlayerInfoReply(
  target: ResolvedTarget,
  seasonId: string,
  seasonName: string | null,
  seasons: Season[],
): Promise<ViewReply | { error: string }> {
  const seasonQuery = `?season=${encodeURIComponent(seasonId)}`;
  const [stats, awards] = await Promise.all([
    fetchJson<StatsResponse>(`${API}/players/${target.uuid}/stats${seasonQuery}`),
    fetchJson<CrownResponse>(`${API}/players/${target.uuid}/awards${seasonQuery}`),
  ]);

  if (stats.threw && awards.threw) {
    return { error: "Could not reach the CrabCraft API. Try again in a bit." };
  }

  const crown = awards.data?.crown ?? null;
  const displayName =
    target.username || stats.data?.username || awards.data?.username || target.uuid.slice(0, 8);

  const buffer = await generatePlayerCard({
    uuid: target.uuid,
    username: displayName,
    discordUsername: target.discordUsername,
    rank: crown?.rank ?? 0,
    points: crown?.crown_score ?? 0,
    gold: crown?.gold ?? 0,
    silver: crown?.silver ?? 0,
    bronze: crown?.bronze ?? 0,
    season: seasonName,
    stats: stats.data?.stats ?? null,
  });

  const file = new AttachmentBuilder(buffer, { name: "playerinfo.png" });
  const components: ViewReply["components"] = [];

  // Only worth a dropdown when there's more than one season to switch between.
  if (seasons.length > 1) {
    const menu = new StringSelectMenuBuilder()
      .setCustomId(`${SEASON_SELECT_PREFIX}${target.uuid}`)
      .setPlaceholder(seasonName ?? "Select a season")
      .addOptions(
        seasons.slice(0, 25).map((s) =>
          new StringSelectMenuOptionBuilder()
            .setLabel(s.name)
            .setValue(s.id)
            .setDefault(s.id === seasonId),
        ),
      );
    components.push(new ActionRowBuilder<StringSelectMenuBuilder>().addComponents(menu));
  }

  return { components, files: [file] };
}

/**
 * Choose which season to show first: the current season when the player has data
 * for it, otherwise their most recent season with data, otherwise the current
 * season id (the card then renders empty).
 */
export function pickInitialSeason(
  seasons: Season[],
  current: Season | null,
): { id: string; name: string | null } {
  if (current && seasons.some((s) => s.id === current.id)) {
    return { id: current.id, name: current.name };
  }
  if (seasons.length > 0) return { id: seasons[0].id, name: seasons[0].name };
  return { id: current?.id ?? "", name: current?.name ?? null };
}
