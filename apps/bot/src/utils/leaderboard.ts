import {
  TextDisplayBuilder,
  ContainerBuilder,
  ButtonBuilder,
  ButtonStyle,
  ActionRowBuilder,
} from "discord.js";
import logger from "./logger.js";
import rankEmojis from "../data/rankEmojis.json" with { type: "json" };

/** Used when no season was given and none is stored in the leaderboard state. */
export const DEFAULT_LEADERBOARD_SEASON = 6;

export interface PlayerStats {
  uuid: string;
  name: string;
  total: number;
  gold: number;
  silver: number;
  bronze: number;
}

export interface LeaderboardData {
  topPlayers: PlayerStats[];
  totalPoints: number;
  averagePoints: number;
  playerCount: number;
}

export async function fetchLeaderboardData(
  season: number,
): Promise<LeaderboardData | null> {
  try {
    const response = await fetch(
      `https://api.crabcraft.net/awards/crowns?limit=100&season=${season}`,
    );
    if (!response.ok) {
      throw new Error(`Failed to fetch leaderboard: ${response.statusText}`);
    }

    const data = await response.json();
    const entries = data.leaderboard;

    if (!Array.isArray(entries)) {
      throw new Error("Invalid data format: 'leaderboard' is not an array");
    }

    const allStats: PlayerStats[] = entries.map((entry: any) => ({
      uuid: entry.uuid,
      name: entry.username || "Unknown",
      total: entry.crown_score,
      gold: entry.gold,
      silver: entry.silver,
      bronze: entry.bronze,
    }));

    const totalPoints = allStats.reduce((sum, p) => sum + p.total, 0);
    const playerCount = data.total || allStats.length;
    const averagePoints = playerCount > 0 ? totalPoints / playerCount : 0;

    const topPlayers = allStats.slice(0, 10);

    return {
      topPlayers,
      totalPoints,
      averagePoints,
      playerCount,
    };
  } catch (error) {
    logger.error("Error fetching leaderboard data:", error);
    return null;
  }
}


export function buildLeaderboardComponents(
  data: LeaderboardData,
  emojis?: Map<string, string>,
  season: number = DEFAULT_LEADERBOARD_SEASON,
) {
  const headerContainer = new ContainerBuilder().addTextDisplayComponents(
    (td) => td.setContent("# 🏆 CrabCraft Hall of Fame"),
    (td) => td.setContent("-# Top 10 players based on total awards earned"),
  );

  const rankingsText =
    data.topPlayers
      .map((p, i) => {
        const rankNum = `${i + 1}`;
        const rankId = (rankEmojis as Record<string, string>)[rankNum];
        const rankStr = rankId ? `<:rank_${rankNum}:${rankId}>` : `**${rankNum}.**`;
        const head = emojis?.get(p.uuid);
        const headStr = head ? ` ${head}` : "";
        return `${rankStr}${headStr} **${p.name}** - ${p.total.toLocaleString()} points (<:1st:1397647414982086829> ${p.gold}, <:2nd:1397647416681042011> ${p.silver}, <:3rd:1397647418492850226> ${p.bronze})`;
      })
      .join("\n") ||
    "No player data yet. Rankings will appear here once people are playing.";

  const rankingsContainer = new ContainerBuilder().addTextDisplayComponents(
    (td) => td.setContent("## 🎖️ Rankings"),
    (td) => td.setContent(rankingsText),
  );

  const statsText =
    data.topPlayers.length > 0
      ? `## 📊 Server Statistics\n**Total Points:** ${data.totalPoints.toLocaleString()}\n**Average:** ${Math.floor(data.averagePoints).toLocaleString()}\n-# Updates every 5 minutes`
      : "## 📊 Server Statistics\nNothing to count yet.\n-# Updates every 5 minutes";

  const viewStatsButton = new ButtonBuilder()
    .setLabel("View Full Stats")
    .setStyle(ButtonStyle.Link)
    .setURL(`https://www.crabcraft.net/leaderboard?season=${season}`);

  const statsContainer = new ContainerBuilder().addTextDisplayComponents((td) =>
    td.setContent(statsText),
  );

  const viewStatsRow = new ActionRowBuilder<ButtonBuilder>().addComponents(
    viewStatsButton,
  );

  return [headerContainer, rankingsContainer, statsContainer, viewStatsRow];
}
