import type { Metadata } from "next";
import Link from "next/link";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import { formatValue } from "@/lib/formatValue";
import { notFound } from "next/navigation";
import LeaderboardVisibilityToggle from "@/components/LeaderboardVisibilityToggle";
import {
  LeaderboardPlayerLink,
  LeaderboardPlayerAvatar,
  leaderboardPlayerName,
} from "@/components/LeaderboardPlayer";
import { leaderboardApiUrl } from "@/lib/leaderboardApi";

interface Props {
  params: Promise<{ key: string }>;
  searchParams: Promise<{ show_hidden?: string }>;
}

interface ProxyAwardDef {
  id: string;
  title: string;
  description: string;
  unit: string;
  bucket: string;
  icon: string;
}

interface ProxyLeaderboardEntry {
  rank: number;
  uuid: string | null;
  hidden?: boolean;
  username: string | null;
  nickname: string | null;
  score: number;
  medal: number;
}

interface ProxyAwardResponse {
  award: ProxyAwardDef;
  leaderboard: ProxyLeaderboardEntry[];
}

async function fetchAwardLeaderboard(key: string, showHidden = false): Promise<ProxyAwardResponse | null> {
  const res = await fetch(leaderboardApiUrl(`/awards/${encodeURIComponent(key)}${showHidden ? "?show_hidden=true" : ""}`), {
    next: { revalidate: 30 },
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`API returned ${res.status}`);
  return await res.json();
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { key } = await params;
  const data = await fetchAwardLeaderboard(key);
  const title = data?.award.title ?? key;
  return {
    title: `${title} Leaderboard`,
    description: `View the ${title} award leaderboard on CrabCraft.`,
  };
}

export default async function AwardLeaderboardPage({ params, searchParams }: Props) {
  const { key } = await params;
  const showHidden = (await searchParams).show_hidden === "true";
  const data = await fetchAwardLeaderboard(key, showHidden);

  if (!data) notFound();

  const meta = data.award;
  const entries = data.leaderboard.map((entry) => ({
    ...entry,
    displayName: leaderboardPlayerName(entry),
  }));
  const awardUnits: Record<string, string> = { [meta.id]: meta.unit };

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-3xl">
        <div className="text-center mb-10 animate-in">
          <div className="flex items-center justify-center gap-3 mb-2">
            <PixelIcon
              src={meta.icon}
              size={48}
            />
            <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
              {meta.title}
            </h1>
          </div>
          {meta.description && (
            <p className="text-gray-600 dark:text-gray-400">
              {meta.description}
            </p>
          )}
          <p className="mt-1 text-sm text-gray-400 dark:text-gray-500">
            {entries.length} players ranked
          </p>
        </div>

        <div className="flex justify-end mb-2 px-1">
          <LeaderboardVisibilityToggle showHidden={showHidden} />
        </div>

        <Squircle
          cornerRadius={32}
          className="bg-paper-2 overflow-hidden animate-in"
          style={{ animationDelay: "0.25s" }}
        >
          {entries.length > 0 ? (
            <>
              <div className="flex items-center gap-3 px-4 sm:gap-5 sm:px-6 py-3 text-[10px] sm:text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider border-b border-gray-200 dark:border-[#3d3028]">
                <span className="w-9 sm:w-14 shrink-0">Rank</span>
                <span className="flex-1">Player</span>
                <span className="shrink-0">Value</span>
              </div>
              {entries.map((entry, i) => (
                <LeaderboardPlayerLink
                  key={entry.uuid ?? `hidden-${i}`}
                  player={entry}
                  className={`flex items-center gap-3 px-4 sm:gap-5 sm:px-6 py-3 transition-colors hover:bg-orange-50/60 dark:hover:bg-[#2a221b] ${
                    i % 2 === 0
                      ? "bg-paper-2"
                      : "bg-paper/60 dark:bg-[#2a221b]/40"
                  }`}
                >
                  <span
                    className={`w-9 sm:w-14 text-sm font-bold shrink-0 ${
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
                  <LeaderboardPlayerAvatar
                    player={entry}
                    size={32}
                    imgClassName="rounded"
                  />
                  <span className="flex-1 min-w-0 font-bold text-sm text-gray-800 dark:text-gray-200">
                    <span className={entry.hidden ? "block" : "block truncate"}>{entry.displayName}</span>
                  </span>
                  <span className="text-sm font-bold text-gray-600 dark:text-gray-400 shrink-0">
                    {formatValue(entry.score, key, awardUnits)}
                  </span>
                </LeaderboardPlayerLink>
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
          style={{ animationDelay: "0.35s" }}
        >
          <Link
            href={`/awards${showHidden ? "?show_hidden=true" : ""}`}
            className="text-sm text-gray-500 dark:text-gray-400 hover:text-orange-500 transition-colors"
          >
            &larr; Back to Awards
          </Link>
        </div>
      </div>
    </div>
  );
}
