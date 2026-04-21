import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import Squircle from "@/components/Squircle";
import ServerSelect from "@/components/ServerSelect";
import { formatValue } from "@/lib/formatValue";
import { getAward, AWARDS } from "@crabcraft/shared/awards";
import {
  getAwardLeaderboard,
  getAwardServers,
  getCurrentSeason,
  AWARD_AGGREGATE_SERVER_ID,
} from "@/lib/queries";
import { notFound } from "next/navigation";

interface SearchParams {
  server?: string;
}

interface Props {
  params: Promise<{ key: string }>;
  searchParams: Promise<SearchParams>;
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { key } = await params;
  const meta = getAward(key);
  const title = meta?.title ?? key;
  return {
    title: `${title} Leaderboard`,
    description: `View the ${title} award leaderboard on CrabCraft.`,
  };
}

export default async function AwardLeaderboardPage({
  params,
  searchParams,
}: Props) {
  const [{ key }, { server }] = await Promise.all([params, searchParams]);
  const meta = getAward(key);
  if (!meta) notFound();

  const currentSeason = await getCurrentSeason();
  const seasonId = currentSeason?.id;

  const serverId = server && server.length > 0 ? server : AWARD_AGGREGATE_SERVER_ID;

  const [entries, servers] = seasonId
    ? await Promise.all([
        getAwardLeaderboard(key, seasonId, serverId, 100),
        getAwardServers(seasonId),
      ])
    : [[], []];

  const awardUnits: Record<string, string> = {};
  for (const id of Object.keys(AWARDS)) awardUnits[id] = AWARDS[id].unit;

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-3xl">
        <div className="text-center mb-10 animate-in">
          <div className="flex items-center justify-center gap-3 mb-2">
            <Image
              src={meta.icon}
              alt=""
              width={48}
              height={48}
              unoptimized
              className="pixelated"
            />
            <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
              {meta.title}
            </h1>
          </div>
          {meta.desc && (
            <p className="text-gray-600 dark:text-gray-400">{meta.desc}</p>
          )}
          <p className="mt-1 text-sm text-gray-400 dark:text-gray-500">
            {entries.length} players ranked
          </p>
        </div>

        {servers.length > 0 && (
          <div className="flex justify-center mb-6 animate-in">
            <ServerSelect
              servers={servers}
              current={serverId}
              basePath={`/awards/${key}`}
            />
          </div>
        )}

        <Squircle
          cornerRadius={32}
          className="bg-paper-2 overflow-hidden animate-in"
          style={{ animationDelay: "0.1s" }}
        >
          {entries.length > 0 ? (
            <>
              <div className="flex items-center gap-5 px-6 py-3 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider border-b border-gray-200 dark:border-[#3d3028]">
                <span className="w-14">Rank</span>
                <span className="flex-1">Player</span>
                <span className="shrink-0">Value</span>
              </div>
              {entries.map((entry, i) => (
                <Link
                  key={entry.minecraft_uuid}
                  href={`/stats/${entry.minecraft_uuid}`}
                  className={`flex items-center gap-5 px-6 py-3 transition-colors hover:bg-orange-50/60 dark:hover:bg-[#2a221b] ${
                    i % 2 === 0
                      ? "bg-paper-2"
                      : "bg-paper/60 dark:bg-[#2a221b]/40"
                  }`}
                >
                  <span
                    className={`w-14 text-sm font-bold shrink-0 ${
                      entry.rank === 1
                        ? "text-yellow-500"
                        : entry.rank === 2
                          ? "text-gray-400"
                          : entry.rank === 3
                            ? "text-amber-600"
                            : "text-gray-400 dark:text-gray-500"
                    }`}
                  >
                    {entry.rank}
                    {entry.rank === 1
                      ? "st"
                      : entry.rank === 2
                        ? "nd"
                        : entry.rank === 3
                          ? "rd"
                          : "th"}
                  </span>
                  <Image
                    src={`https://mc-heads.net/avatar/${entry.minecraft_uuid}/64.png`}
                    alt={entry.minecraft_username ?? ""}
                    width={32}
                    height={32}
                    className="rounded shrink-0"
                  />
                  <span className="flex-1 font-bold text-sm text-gray-800 dark:text-gray-200 truncate">
                    {entry.minecraft_username ?? "Unknown"}
                  </span>
                  <span className="text-sm font-bold text-gray-600 dark:text-gray-400 shrink-0">
                    {formatValue(entry.score, key, awardUnits)}
                  </span>
                </Link>
              ))}
            </>
          ) : (
            <div className="px-6 py-12 text-center text-gray-500 dark:text-gray-400">
              <p className="text-lg font-bold">No rankings available</p>
              <p className="text-sm mt-1">This award has no data yet</p>
            </div>
          )}
        </Squircle>

        <div
          className="mt-8 text-center animate-in"
          style={{ animationDelay: "0.2s" }}
        >
          <Link
            href="/awards"
            className="text-sm text-gray-500 dark:text-gray-400 hover:text-orange-500 transition-colors"
          >
            &larr; Back to Awards
          </Link>
        </div>
      </div>
    </div>
  );
}
