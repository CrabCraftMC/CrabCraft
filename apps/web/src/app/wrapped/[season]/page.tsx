import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/lib/auth";
import {
  getMinecraftUuid,
  getMinecraftUsername,
  getPlayerStats,
  getServerAverages,
  getPlayerRank,
} from "@/lib/queries";
import WrappedContainer from "@/components/wrapped/WrappedContainer";

interface Props {
  params: Promise<{ season: string }>;
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { season } = await params;
  return {
    title: `Wrapped Season ${season}`,
    description: `View your personalised CrabCraft Wrapped stats for Season ${season}.`,
    alternates: {
      canonical: `https://crabcraft.net/wrapped/${season}`,
    },
  };
}

export default async function WrappedSeasonPage({ params }: Props) {
  const { season } = await params;
  const session = await auth();

  if (!session) {
    redirect("/login");
  }

  const user = session.user;
  let wrappedData: any = null;
  let error = "";

  try {
    const [mcUuid, mcUsername] = await Promise.all([
      getMinecraftUuid(user.discordId),
      getMinecraftUsername(user.discordId),
    ]);

    if (!mcUuid) {
      error = "no-mc";
    } else {
      const stats = await getPlayerStats(mcUuid, season);
      if (!stats) {
        error = "no-data";
      } else {
        const rankCategories = [
          "play_time_seconds",
          "total_blocks_mined",
          "mob_kills",
          "total_distance_m",
          "deaths",
          "total_items_crafted",
        ] as const;

        const [averages, ...rankValues] = await Promise.all([
          getServerAverages(season),
          ...rankCategories.map((cat) => getPlayerRank(mcUuid, season, cat)),
        ]);

        const ranks: Record<string, number> = {};
        rankCategories.forEach((cat, i) => {
          ranks[cat] = rankValues[i];
        });

        wrappedData = JSON.parse(
          JSON.stringify(
            {
              stats,
              averages,
              ranks,
              playerName: mcUsername || user.name,
              playerUuid: mcUuid,
              season,
              totalPlayers: (averages as any).player_count || 0,
            },
            (_, v) => (typeof v === "bigint" ? Number(v) : v)
          )
        );
      }
    }
  } catch (e) {
    console.error("Failed to load wrapped data:", e);
    error = "fetch-error";
  }

  if (wrappedData) {
    return <WrappedContainer data={wrappedData} />;
  }

  return (
    <div className="min-h-screen flex items-center justify-center pt-24">
      <div className="text-center">
        <h1 className="text-2xl font-bold text-gray-800 dark:text-gray-200">
          {error === "no-mc"
            ? "No Minecraft account linked"
            : error === "no-data"
              ? "No data for this season"
              : "Something went wrong"}
        </h1>
        <p className="text-gray-500 dark:text-gray-400 mt-2">
          {error === "no-mc"
            ? "Your Discord account needs a linked Minecraft UUID"
            : error === "no-data"
              ? "You don't have stats recorded for this season"
              : "Please try again later"}
        </p>
        <Link
          href="/wrapped"
          className="inline-block mt-4 text-orange-500 hover:underline"
        >
          Back to seasons
        </Link>
      </div>
    </div>
  );
}
