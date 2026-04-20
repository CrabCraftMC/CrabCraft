import { gunzipSync } from "node:zlib";
import {
  TextDisplayBuilder,
  ContainerBuilder,
  ButtonBuilder,
  ButtonStyle,
  ActionRowBuilder,
} from "discord.js";
import logger from "./logger.js";
import rankEmojis from "../data/rankEmojis.json" with { type: "json" };

const API_URL = "https://map.crabcraft.net/stats/data/summary.json.gz";

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

export async function fetchLeaderboardData(): Promise<LeaderboardData | null> {
  try {
    const response = await fetch(API_URL);
    if (!response.ok) {
      throw new Error(`Failed to fetch leaderboard: ${response.statusText}`);
    }

    const buffer = await response.arrayBuffer();
    const decompressed = gunzipSync(Buffer.from(buffer));
    const data = JSON.parse(decompressed.toString("utf-8"));

    const { hof, players } = data;

    if (!Array.isArray(hof)) {
      throw new Error("Invalid data format: 'hof' is not an array");
    }

    // map hof to readable stats
    const allStats: PlayerStats[] = hof.map((entry: any) => {
      const uuid = entry.uuid;
      const [total, gold, silver, bronze] = entry.value;
      const name = players[uuid]?.name || "Unknown";
      return { uuid, name, total, gold, silver, bronze };
    });

    // Calculate global stats
    const totalPoints = allStats.reduce((sum, p) => sum + p.total, 0);
    const playerCount = allStats.length;
    const averagePoints = playerCount > 0 ? totalPoints / playerCount : 0;

    // Get top 10
    const topPlayers = allStats.sort((a, b) => b.total - a.total).slice(0, 10);

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


export function buildLeaderboardComponents(data: LeaderboardData, emojis?: Map<string, string>) {
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
      .join("\n") || "No data available.";

  const rankingsContainer = new ContainerBuilder().addTextDisplayComponents(
    (td) => td.setContent("## 🎖️ Rankings"),
    (td) => td.setContent(rankingsText),
  );

  const statsText = `## 📊 Server Statistics\n**Total Points:** ${data.totalPoints.toLocaleString()}\n**Average:** ${Math.floor(data.averagePoints).toLocaleString()}\n-# Updates every 5 minutes`;

  const viewStatsButton = new ButtonBuilder()
    .setLabel("View Full Stats")
    .setStyle(ButtonStyle.Link)
    .setURL("https://www.crabcraft.net/leaderboard");

  const statsContainer = new ContainerBuilder().addTextDisplayComponents((td) =>
    td.setContent(statsText),
  );

  const viewStatsRow = new ActionRowBuilder<ButtonBuilder>().addComponents(
    viewStatsButton,
  );

  return [headerContainer, rankingsContainer, statsContainer, viewStatsRow];
}
