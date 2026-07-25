import { unstable_cache } from "next/cache";

const CRABCRAFT_GUILD_ID = "1215756131671212163";
const CURRENT_SEASON_ROLE_ID = "1516779370109206578";

interface DiscordGuildMember {
  roles?: string[];
  user?: { id?: string };
}

async function fetchCurrentSeasonWhitelistedPlayerCount(): Promise<number> {
  const token = process.env.DISCORD_BOT_TOKEN;
  if (!token) throw new Error("DISCORD_BOT_TOKEN is not configured");

  let after = "0";
  let playerCount = 0;

  while (true) {
    const response = await fetch(
      `https://discord.com/api/v10/guilds/${CRABCRAFT_GUILD_ID}/members?limit=1000&after=${after}`,
      {
        headers: { Authorization: `Bot ${token}` },
        cache: "no-store",
        signal: AbortSignal.timeout(15000),
      },
    );
    if (!response.ok) {
      throw new Error(`Discord member request failed with ${response.status}`);
    }

    const members = (await response.json()) as DiscordGuildMember[];
    playerCount += members.filter((member) =>
      member.roles?.includes(CURRENT_SEASON_ROLE_ID),
    ).length;

    if (members.length < 1000) return playerCount;

    const lastMemberId = members.at(-1)?.user?.id;
    if (!lastMemberId) {
      throw new Error("Discord member response is missing an ID");
    }
    after = lastMemberId;
  }
}

export const getCurrentSeasonWhitelistedPlayerCount = unstable_cache(
  fetchCurrentSeasonWhitelistedPlayerCount,
  ["current-season-whitelisted-player-count"],
  { revalidate: 300 },
);
