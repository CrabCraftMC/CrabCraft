import type { Metadata } from "next";
import PlayerStatsPage from "@/components/PlayerStatsPage";
import {
  getPlayerRole,
  getJoinedSeason,
  getUserByIdentifier,
  getPlayerProfile,
  getCurrentSeason,
  getPlayerCrownScore,
  getPlayerAwardScores,
  getAwardDefinitions,
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
  const canonical =
    isUuid && name !== identifier
      ? `https://crabcraft.net/stats/${identifier}`
      : undefined;

  return {
    title: name || "Player Stats",
    description: `View ${name}'s stats, rank, and awards on CrabCraft — a whitelisted Minecraft survival server.`,
    ...(canonical && { alternates: { canonical } }),
    openGraph: {
      title: `${name} - CrabCraft`,
      description: `View ${name}'s stats and awards on CrabCraft.`,
      images:
        name !== identifier
          ? [`https://mc-heads.net/avatar/${identifier}/256.png`]
          : undefined,
    },
  };
}

export default async function StatsPage({ params }: Props) {
  const { slug } = await params;
  const identifier = (slug?.join("/") || "").trim();

  const [dbUser, awardDefs] = await Promise.all([
    getUserByIdentifier(identifier).catch(() => null),
    getAwardDefinitions().catch(() => []),
  ]);

  const playerData = {
    nickname: dbUser?.minecraft_username ?? identifier,
    uuid: dbUser?.minecraft_uuid ?? "",
    rank: 0,
    points: 0,
    gold: 0,
    silver: 0,
    bronze: 0,
    found: Boolean(dbUser?.minecraft_uuid && dbUser?.minecraft_username),
    role: dbUser?.role ?? "unverified",
    joinedSeason: null as string | null,
  };

  const awardUnits: Record<string, string> = {};
  const awardsById: Record<
    string,
    { title: string; description: string; icon: string }
  > = {};
  for (const d of awardDefs) {
    awardUnits[d.id] = d.unit;
    awardsById[d.id] = {
      title: d.title,
      description: d.description,
      icon: d.icon,
    };
  }

  if (!playerData.found) {
    return (
      <PlayerStatsPage
        {...playerData}
        detailedStats={null}
        awardsById={awardsById}
        localization={null}
        awardUnits={awardUnits}
        profile={null}
      />
    );
  }

  const currentSeason = await getCurrentSeason();
  const seasonId = currentSeason?.id;

  const [joinedSeason, crown, detailedStats, profile] = await Promise.all([
    getJoinedSeason(playerData.uuid).catch(() => null),
    seasonId
      ? getPlayerCrownScore(playerData.uuid, seasonId).catch(() => null)
      : Promise.resolve(null),
    seasonId
      ? getPlayerAwardScores(playerData.uuid, seasonId).catch(() => ({}))
      : Promise.resolve({}),
    getPlayerProfile(playerData.uuid).catch(() => null),
  ]);

  // Use current role from DB (already in dbUser) but refresh via cache helper for consistency.
  let role = playerData.role;
  try {
    role = await getPlayerRole(playerData.uuid);
  } catch {}

  const enriched = {
    ...playerData,
    role,
    joinedSeason,
    rank: crown?.rank ?? 0,
    points: crown?.crown_score ?? 0,
    gold: crown?.gold ?? 0,
    silver: crown?.silver ?? 0,
    bronze: crown?.bronze ?? 0,
  };

  return (
    <PlayerStatsPage
      {...enriched}
      detailedStats={detailedStats}
      awardsById={awardsById}
      localization={null}
      awardUnits={awardUnits}
      profile={profile}
    />
  );
}
