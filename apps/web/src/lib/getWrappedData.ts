import { redirect } from "next/navigation";
import { auth } from "@/lib/auth";
import {
  getPlayerStats,
  getServerAverages,
  getPlayerRank,
} from "@/lib/queries";
import type { WrappedData, WrappedDataResult } from "./wrappedTypes";

const RANK_CATEGORIES = [
  "play_time_seconds",
  "total_blocks_mined",
  "mob_kills",
  "total_distance_m",
  "deaths",
  "total_items_crafted",
] as const;

export async function getWrappedData(season: string): Promise<WrappedDataResult> {
  const session = await auth();
  if (!session) {
    redirect("/login");
  }

  const user = session.user;

  try {
    const mcUuid = user.minecraftUuid;

    if (!mcUuid) {
      return { kind: "no-mc" };
    }

    const stats = await getPlayerStats(mcUuid, season);
    if (!stats) {
      return { kind: "no-data" };
    }

    const [averages, ...rankValues] = await Promise.all([
      getServerAverages(season),
      ...RANK_CATEGORIES.map((cat) => getPlayerRank(mcUuid, season, cat)),
    ]);

    const ranks: Record<string, number> = {};
    RANK_CATEGORIES.forEach((cat, i) => {
      ranks[cat] = rankValues[i] as number;
    });

    const data = JSON.parse(
      JSON.stringify(
        {
          stats,
          averages,
          ranks,
          playerName: user.minecraftUsername || user.name,
          playerUuid: mcUuid,
          season,
          totalPlayers:
            (averages as Record<string, number>).player_count || 0,
        },
        (_, v) => (typeof v === "bigint" ? Number(v) : v)
      )
    ) as WrappedData;

    return { kind: "ok", data };
  } catch (e) {
    console.error("Failed to load wrapped data:", e);
    return { kind: "fetch-error" };
  }
}
