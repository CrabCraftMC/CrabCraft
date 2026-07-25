import type { Metadata } from "next";
import Link from "next/link";
import { auth } from "@/lib/auth";
import { getSeasons, getPlayerSeasons } from "@/lib/queries";
import Squircle from "@/components/Squircle";
import SeasonTileImage from "@/components/wrapped/SeasonTileImage";
import type { Season } from "@crabcraft/shared/types";

export const metadata: Metadata = {
  title: "Wrapped",
  description: "View your CrabCraft Wrapped stats for each season.",
};

export default async function WrappedPage() {
  const [session, seasons] = await Promise.all([
    auth(),
    getSeasons()
      .then((items) => items.filter((season) => !season.is_current).reverse())
      .catch(() => [] as Season[]),
  ]);
  const user = session?.user ?? null;
  let playerSeasonIds: Set<string> = new Set();

  if (user?.minecraftUuid) {
    try {
      const playerSeasons = await getPlayerSeasons(user.minecraftUuid);
      playerSeasonIds = new Set(playerSeasons.map((season) => season.id));
    } catch {
      // The season grid remains available without per-player data.
    }
  }

  const sizePattern = [
    "sm:col-span-2 min-h-[200px]",
    "min-h-[200px]",
    "min-h-[200px]",
    "min-h-[200px]",
    "sm:col-span-2 lg:col-span-1 min-h-[200px]",
    "lg:col-span-2 min-h-[200px]",
    "min-h-[200px]",
  ];

  return (
    <div className="pt-24 pb-8">
      <div className="container mx-auto px-4">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Wrapped
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Choose a season to view your stats
          </p>
        </div>

        {!user && (
          <Squircle
            cornerRadius={32}
            className="bg-gradient-to-br from-[#F97316] to-[#FB923C] p-8 lg:p-10 text-center mb-10 animate-in"
            style={{ animationDelay: "0.1s" }}
          >
            <h2 className="text-2xl font-bold text-white mb-2">
              Sign in to view your Wrapped
            </h2>
            <p className="text-white/70 mb-4">
              Connect your Discord account to see your personalised season stats
            </p>
            <Link
              href="/login"
              className="inline-block bg-white/20 backdrop-blur-sm border border-white/30 hover:bg-white/30 text-white font-bold py-3 px-8 rounded-full transition-all hover:scale-105"
            >
              Sign In with Discord
            </Link>
          </Squircle>
        )}

        {seasons.length > 0 ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 max-w-5xl mx-auto">
            {seasons.map((season, i) => {
              const hasData = playerSeasonIds.has(season.id);
              const canClick = user && hasData;
              const sizeClass = sizePattern[i % sizePattern.length];

              const content = (
                <>
                  <div className="absolute inset-0 bg-gradient-to-br from-[#F97316] to-[#FB923C]">
                    <SeasonTileImage
                      src={`/wrapped/${season.id}.webp`}
                      alt={season.name}
                      className={`object-cover ${season.id === "5" ? "object-right" : "object-center"}`}
                    />
                  </div>
                  <div className="absolute inset-0 bg-gradient-to-t from-black/70 via-black/20 to-transparent" />

                  {user && !hasData && (
                    <div className="absolute top-4 right-4 z-20">
                      <span className="bg-black/50 text-white/70 text-xs font-bold px-3 py-1 rounded-full">
                        No data for {season.name}
                      </span>
                    </div>
                  )}
                  {!user && (
                    <div className="absolute top-4 right-4 z-20">
                      <span className="bg-black/50 text-white/70 text-xs font-bold px-3 py-1 rounded-full">
                        Sign in to view
                      </span>
                    </div>
                  )}

                  <div className="relative z-10">
                    <h2 className="text-xl lg:text-2xl font-bold text-white">
                      {season.name}
                    </h2>
                    {(season.start_date || season.end_date) && (
                      <p className="text-white/60 text-sm mt-1">
                        {season.start_date || "?"} —{" "}
                        {season.end_date || "Present"}
                      </p>
                    )}
                  </div>
                </>
              );

              if (canClick) {
                return (
                  <Squircle
                    cornerRadius={32}
                    key={season.id}
                    className={`card-hover relative overflow-hidden flex flex-col justify-end ${sizeClass}`}
                  >
                    <Link
                      href={`/wrapped/${season.id}`}
                      className="block p-6 relative overflow-hidden flex flex-col justify-end h-full cursor-pointer"
                    >
                      {content}
                    </Link>
                  </Squircle>
                );
              }

              return (
                <Squircle
                  cornerRadius={32}
                  key={season.id}
                  className={`card-hover relative overflow-hidden flex flex-col justify-end p-6 ${sizeClass} ${!user ? "cursor-pointer" : "cursor-not-allowed"} opacity-60 grayscale-[30%]`}
                >
                  {!user ? (
                    <Link href="/login" className="absolute inset-0 z-30" />
                  ) : null}
                  {content}
                </Squircle>
              );
            })}
          </div>
        ) : (
          <div className="text-center text-gray-500 dark:text-gray-400 py-16">
            <p className="text-lg font-bold">No seasons available yet</p>
            <p className="text-sm mt-1">Check back later</p>
          </div>
        )}
      </div>
    </div>
  );
}
