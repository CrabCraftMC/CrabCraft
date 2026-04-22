import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import Squircle from "@/components/Squircle";
import ServerSelect from "@/components/ServerSelect";
import { formatValue } from "@/lib/formatValue";
import { notFound } from "next/navigation";

interface SearchParams {
  server?: string;
}

interface Props {
  params: Promise<{ key: string }>;
  searchParams: Promise<SearchParams>;
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
  uuid: string;
  username: string | null;
  score: number;
  medal: number;
}

interface ProxyAwardResponse {
  award: ProxyAwardDef;
  leaderboard: ProxyLeaderboardEntry[];
}

async function fetchAwardLeaderboard(key: string, server?: string): Promise<ProxyAwardResponse | null> {
  const url = new URL(`https://api.crabcraft.net/awards/${key}`);
  if (server) url.searchParams.set("server", server);
  try {
    const res = await fetch(url, { next: { revalidate: 30 } });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

async function fetchAwardServers(): Promise<string[]> {
  try {
    const res = await fetch("https://api.crabcraft.net/awards", { next: { revalidate: 60 } });
    if (!res.ok) return [];
    const data = await res.json();
    return data.servers ?? [];
  } catch {
    return [];
  }
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

export default async function AwardLeaderboardPage({
  params,
  searchParams,
}: Props) {
  const [{ key }, { server }] = await Promise.all([params, searchParams]);
  const [data, servers] = await Promise.all([
    fetchAwardLeaderboard(key, server),
    fetchAwardServers(),
  ]);

  if (!data) notFound();

  const meta = data.award;
  const entries = data.leaderboard;
  const serverId = server && server.length > 0 ? server : "";
  const awardUnits: Record<string, string> = { [meta.id]: meta.unit };

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
          {meta.description && (
            <p className="text-gray-600 dark:text-gray-400">
              {meta.description}
            </p>
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
                  key={entry.uuid}
                  href={`/stats/${entry.uuid}`}
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
                    src={`https://mc-heads.net/avatar/${entry.uuid}/64.png`}
                    alt={entry.username ?? ""}
                    width={32}
                    height={32}
                    className="rounded shrink-0"
                  />
                  <span className="flex-1 font-bold text-sm text-gray-800 dark:text-gray-200 truncate">
                    {entry.username ?? "Unknown"}
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
