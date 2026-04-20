import type { Metadata } from "next";
import PlayerStatsPage from "@/components/PlayerStatsPage";
import { fetchGzipJson } from "@/lib/fetchGzip";
import {
  isPlayerAdmin,
  getJoinedSeason,
  getUserByIdentifier,
  getPlayerProfile,
} from "@/lib/queries";

interface Props {
  params: Promise<{ slug?: string[] }>;
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { slug } = await params;
  const identifier = slug?.join("/") || "";

  let name = identifier;
  try {
    const dbUser = await getUserByIdentifier(identifier);
    if (dbUser?.minecraft_username) {
      name = dbUser.minecraft_username;
    }
  } catch {}

  const isUuid = identifier.includes("-") || identifier.length === 32;
  const canonical = isUuid && name !== identifier
    ? `https://crabcraft.net/stats/${identifier}`
    : undefined;

  return {
    title: name || "Player Stats",
    description: `View ${name}'s stats, rank, and awards on CrabCraft — a whitelisted Minecraft survival server.`,
    ...(canonical && { alternates: { canonical } }),
    openGraph: {
      title: `${name} - CrabCraft`,
      description: `View ${name}'s stats and awards on CrabCraft.`,
      images: name !== identifier
        ? [`https://mc-heads.net/avatar/${identifier}/256.png`]
        : undefined,
    },
  };
}

export default async function StatsPage({ params }: Props) {
  const { slug } = await params;
  const identifier = (slug?.join("/") || "").trim();

  let playerData = {
    nickname: identifier,
    uuid: "",
    rank: 0,
    points: 0,
    gold: 0,
    silver: 0,
    bronze: 0,
    found: false,
    isAdmin: false,
    joinedSeason: null as string | null,
  };

  let awardUnits: Record<string, string> | null = null;

  const json = await fetchGzipJson<any>(
    "https://map.crabcraft.net/stats/data/summary.json.gz"
  );

  if (json) {
    const search = identifier.toLowerCase();
    const isUuid = search.includes("-") || search.length === 32;

    // Extract award unit types
    if (json.awards) {
      awardUnits = {};
      for (const [key, val] of Object.entries(json.awards) as [string, any][]) {
        if (val.unit) awardUnits[key] = val.unit;
      }
    }

    for (let i = 0; i < json.hof.length; i++) {
      const entry = json.hof[i];
      const player = json.players[entry.uuid];
      const match = isUuid
        ? entry.uuid.toLowerCase() === search
        : player && player.name.toLowerCase() === search;
      if (match && player) {
        let admin = false;
        let joinedSeason: string | null = null;
        try {
          [admin, joinedSeason] = await Promise.all([isPlayerAdmin(entry.uuid), getJoinedSeason(entry.uuid)]);
        } catch {}
        playerData = {
          nickname: player.name,
          uuid: entry.uuid,
          rank: i + 1,
          points: entry.value[0],
          gold: entry.value[1],
          silver: entry.value[2],
          bronze: entry.value[3],
          found: true,
          isAdmin: admin,
          joinedSeason,
        };
        break;
      }
    }

    if (!playerData.found && json.players) {
      for (const [uuid, player] of Object.entries(json.players) as [
        string,
        any,
      ][]) {
        const match = isUuid
          ? uuid.toLowerCase() === search
          : player.name && player.name.toLowerCase() === search;
        if (match) {
          let admin = false;
          let joinedSeason: string | null = null;
          try {
            [admin, joinedSeason] = await Promise.all([isPlayerAdmin(uuid), getJoinedSeason(uuid)]);
          } catch {}
          playerData = {
            nickname: player.name,
            uuid,
            rank: 0,
            points: 0,
            gold: 0,
            silver: 0,
            bronze: 0,
            found: true,
            isAdmin: admin,
            joinedSeason,
          };
          break;
        }
      }
    }
  }

  if (!playerData.found) {
    try {
      const dbUser = await getUserByIdentifier(identifier);
      if (dbUser && dbUser.minecraft_uuid && dbUser.minecraft_username) {
        const joinedSeason = await getJoinedSeason(dbUser.minecraft_uuid);
        playerData = {
          nickname: dbUser.minecraft_username,
          uuid: dbUser.minecraft_uuid,
          rank: 0,
          points: 0,
          gold: 0,
          silver: 0,
          bronze: 0,
          found: true,
          isAdmin: dbUser.is_admin,
          joinedSeason,
        };
      }
    } catch {}
  }

  // Fetch detailed per-player stats + localization in parallel
  let detailedStats: Record<string, { rank?: number; value: number }> | null = null;
  let localization: Record<string, string> | null = null;
  const statsUuid = playerData.uuid || (identifier.includes("-") ? identifier : "");
  const profileUuid = playerData.uuid || statsUuid;
  const profilePromise = profileUuid
    ? getPlayerProfile(profileUuid).catch(() => null)
    : Promise.resolve(null);

  if (statsUuid) {
    const [statsRes, locRes] = await Promise.all([
      fetch(
        `https://map.crabcraft.net/stats/data/playerdata/${statsUuid}.json`,
        { signal: AbortSignal.timeout(10000), next: { revalidate: 60 } }
      ).catch(() => null),
      fetch(
        "https://map.crabcraft.net/stats/localization/en.json",
        { signal: AbortSignal.timeout(10000), next: { revalidate: 3600 } }
      ).catch(() => null),
    ]);
    if (statsRes?.ok) detailedStats = await statsRes.json();
    if (locRes?.ok) localization = await locRes.json();
  }

  // Fetch player profile (Discord username, active status)
  let profile: { discord_username: string | null; active: boolean } | null = null;
  profile = await profilePromise;

  return <PlayerStatsPage {...playerData} detailedStats={detailedStats} localization={localization} awardUnits={awardUnits} profile={profile} />;
}
