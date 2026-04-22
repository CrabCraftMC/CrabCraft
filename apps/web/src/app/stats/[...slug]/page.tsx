import type { Metadata } from "next";
import PlayerStatsPage from "@/components/PlayerStatsPage";
import {
  getPlayerRole,
  getJoinedSeason,
  getUserByIdentifier,
  getPlayerProfile,
} from "@/lib/queries";

interface ProxyAward {
  id: string;
  title: string;
  description: string;
  unit: string;
  bucket: string;
  icon: string;
  leader: { uuid: string; username: string; score: number } | null;
}

interface ProxyPlayerAwards {
  uuid: string;
  username: string | null;
  crown: { rank: number; gold: number; silver: number; bronze: number; crown_score: number } | null;
  scores: Record<string, { rank: number; score: number }>;
}

async function fetchAwardDefinitions(): Promise<ProxyAward[]> {
  try {
    const res = await fetch("https://api.crabcraft.net/awards", { next: { revalidate: 60 } });
    if (!res.ok) return [];
    const data = await res.json();
    return data.awards ?? [];
  } catch {
    return [];
  }
}

async function fetchPlayerAwards(uuid: string): Promise<ProxyPlayerAwards | null> {
  try {
    const res = await fetch(`https://api.crabcraft.net/players/${uuid}/awards`, { next: { revalidate: 30 } });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

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
    fetchAwardDefinitions(),
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

  const [joinedSeason, playerAwards, profile] = await Promise.all([
    getJoinedSeason(playerData.uuid).catch(() => null),
    fetchPlayerAwards(playerData.uuid),
    getPlayerProfile(playerData.uuid).catch(() => null),
  ]);

  // Convert proxy scores format to what PlayerStatsPage expects
  const detailedStats: Record<string, { rank: number; value: number }> = {};
  if (playerAwards?.scores) {
    for (const [awardId, data] of Object.entries(playerAwards.scores)) {
      detailedStats[awardId] = { rank: data.rank, value: data.score };
    }
  }

  let role = playerData.role;
  try {
    role = await getPlayerRole(playerData.uuid);
  } catch {}

  const enriched = {
    ...playerData,
    role,
    joinedSeason,
    rank: playerAwards?.crown?.rank ?? 0,
    points: playerAwards?.crown?.crown_score ?? 0,
    gold: playerAwards?.crown?.gold ?? 0,
    silver: playerAwards?.crown?.silver ?? 0,
    bronze: playerAwards?.crown?.bronze ?? 0,
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
