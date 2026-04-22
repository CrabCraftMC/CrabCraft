import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import Squircle from "@/components/Squircle";
import ServerSelect from "@/components/ServerSelect";

export const metadata: Metadata = {
  title: "Leaderboard",
  description: "View all CrabCraft players ranked by award points.",
};

interface SearchParams {
  server?: string;
}

interface Props {
  searchParams: Promise<SearchParams>;
}

export default async function LeaderboardPage({ searchParams }: Props) {
  const { server } = await searchParams;
  const serverId = server && server.length > 0 ? server : "";

  let players: Array<{
    rank: number;
    uuid: string;
    username: string | null;
    gold: number;
    silver: number;
    bronze: number;
    crown_score: number;
    minecraft_uuid: string;
    minecraft_username: string | null;
  }> = [];
  let servers: string[] = [];

  try {
    const url = new URL("https://api.crabcraft.net/awards/crowns");
    if (server) url.searchParams.set("server", server);
    const res = await fetch(url, { next: { revalidate: 30 } });
    if (res.ok) {
      const data = await res.json();
      players = (data.leaderboard ?? []).map((p: any) => ({
        ...p,
        minecraft_uuid: p.uuid,
        minecraft_username: p.username,
      }));
      servers = data.servers ?? [];
    }
  } catch {}

  const top3 = players.slice(0, 3);
  const podiumStyles = [
    {
      gradient: "from-[#F59E0B] to-[#FBBF24]",
      label: "1",
      textSize: "text-[120px]",
      render: "relaxing",
      imgH: "h-[250px]",
      avatarSize: 72,
      nameSize: "text-xl",
      ptsSize: "text-3xl",
      padding: "p-8",
      order: "order-first sm:order-none",
      mt: "",
    },
    {
      gradient: "from-[#9CA3AF] to-[#D1D5DB]",
      label: "2",
      textSize: "text-[100px]",
      render: "archer",
      imgH: "h-[200px]",
      avatarSize: 56,
      nameSize: "text-lg",
      ptsSize: "text-2xl",
      padding: "p-6",
      order: "",
      mt: "sm:mt-8",
    },
    {
      gradient: "from-[#B45309] to-[#D97706]",
      label: "3",
      textSize: "text-[100px]",
      render: "lunging",
      imgH: "h-[200px]",
      avatarSize: 56,
      nameSize: "text-lg",
      ptsSize: "text-2xl",
      padding: "p-6",
      order: "",
      mt: "sm:mt-8",
    },
  ];

  const podiumOrder = [1, 0, 2];

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Leaderboard
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            {players.length} players ranked by award points
          </p>
          <Link
            href="/awards"
            className="inline-block mt-3 text-sm font-bold text-orange-500 hover:text-orange-600 transition-colors"
          >
            Browse all awards &rarr;
          </Link>
        </div>

        {servers.length > 0 && (
          <div className="flex justify-center mb-6 animate-in">
            <ServerSelect
              servers={servers}
              current={serverId}
              basePath="/leaderboard"
            />
          </div>
        )}

        {top3.length > 2 && (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 lg:gap-6 mb-10">
            {podiumOrder.map((idx) => {
              const player = top3[idx];
              const style = podiumStyles[idx];
              return (
                <Squircle
                  cornerRadius={32}
                  key={player.minecraft_uuid}
                  className={`${style.mt} ${style.order} card-hover animate-in overflow-hidden bg-gradient-to-br ${style.gradient}`}
                  style={{ animationDelay: `${0.1 + idx * 0.05}s` }}
                >
                  <Link
                    href={`/stats/${player.minecraft_uuid}`}
                    className={`block ${style.padding} relative cursor-pointer`}
                  >
                    <span
                      className={`hidden sm:block absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 ${style.textSize} font-bold text-white/10 z-0 select-none pointer-events-none`}
                    >
                      {style.label}
                    </span>
                    <div className="absolute bottom-0 right-0 pointer-events-none z-0 hidden sm:block opacity-30">
                      <Image
                        src={`https://starlightskins.lunareclipse.studio/render/${style.render}/${player.minecraft_uuid}/full`}
                        alt=""
                        width={140}
                        height={280}
                        className={`${style.imgH} w-auto`}
                      />
                    </div>
                    <div className="relative z-10 flex flex-col items-start text-left gap-3">
                      <Image
                        src={`https://mc-heads.net/avatar/${player.minecraft_uuid}/100.png`}
                        alt={player.minecraft_username ?? ""}
                        width={style.avatarSize}
                        height={style.avatarSize}
                        className="rounded-lg bg-white/20"
                      />
                      <div>
                        <p className={`font-bold text-white ${style.nameSize}`}>
                          {player.minecraft_username ?? "Unknown"}
                        </p>
                        <p
                          className={`font-mc text-white/90 ${style.ptsSize} mt-1`}
                        >
                          {player.crown_score} pts
                        </p>
                        <div className="flex gap-3 mt-2 text-xs text-white/70">
                          <span>{player.gold} gold</span>
                          <span>{player.silver} silver</span>
                          <span>{player.bronze} bronze</span>
                        </div>
                      </div>
                    </div>
                  </Link>
                </Squircle>
              );
            })}
          </div>
        )}

        <div
          className="flex justify-end mb-2 px-1 animate-in relative z-30"
          style={{ animationDelay: "0.25s" }}
        >
          <div className="group relative" tabIndex={0}>
            <span className="text-xs text-gray-400 dark:text-gray-500 cursor-pointer hover:text-orange-500 transition-colors">
              How is this calculated?
            </span>
            <div className="absolute right-0 top-full mt-2 w-72 p-3 bg-paper-2 dark:bg-[#2a221b] rounded-xl shadow-lg border border-gray-200 dark:border-[#3d3028] opacity-0 invisible group-hover:opacity-100 group-hover:visible group-focus-within:opacity-100 group-focus-within:visible transition-all duration-200 z-50">
              <p className="text-xs text-gray-600 dark:text-gray-300 leading-relaxed">
                Points are calculated by the amount of medals a player holds.
              </p>
              <p className="text-xs text-gray-600 dark:text-gray-300 leading-relaxed mt-2">
                Medals are earned by placing{" "}
                <span className="text-yellow-500 font-bold">1st</span> (gold),{" "}
                <span className="text-gray-400 font-bold">2nd</span> (silver),
                or{" "}
                <span className="text-amber-600 font-bold">3rd</span> (bronze)
                in an award category.
              </p>
              <p className="text-xs text-gray-600 dark:text-gray-300 leading-relaxed mt-2">
                A <span className="text-yellow-500 font-bold">gold</span> medal
                is worth <span className="font-bold">5</span> points, a{" "}
                <span className="text-gray-400 font-bold">silver</span> medal is
                worth <span className="font-bold">3</span> points and a{" "}
                <span className="text-amber-600 font-bold">bronze</span> medal
                is worth <span className="font-bold">1</span> point.
              </p>
            </div>
          </div>
        </div>

        <Squircle
          cornerRadius={32}
          className="bg-paper-2 shadow-sm overflow-hidden relative animate-in"
          style={{ animationDelay: "0.25s" }}
        >
          <div className="hidden sm:grid grid-cols-10 gap-2 px-6 py-3 bg-paper/80 dark:bg-paper-2/80 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider border-b border-gray-200 dark:border-[#3d3028] relative z-10">
            <div className="col-span-1 text-center">Rank</div>
            <div className="col-span-5">Player</div>
            <div className="col-span-1 text-center">Pts</div>
            <div className="col-span-1 text-center">Gold</div>
            <div className="col-span-1 text-center">Silver</div>
            <div className="col-span-1 text-center">Bronze</div>
          </div>

          {players.map((player, i) => (
            <Link
              key={player.minecraft_uuid}
              href={`/stats/${player.minecraft_uuid}`}
              className={`grid grid-cols-10 gap-2 px-6 py-3 items-center hover:bg-orange-50/60 dark:hover:bg-[#2a221b] transition-colors relative z-10 cursor-pointer ${
                i % 2 === 0
                  ? "bg-paper-2/80"
                  : "bg-paper/60 dark:bg-[#2a221b]/40"
              }`}
            >
              <div className="col-span-2 sm:col-span-1 flex items-center justify-center">
                <span
                  className={`text-sm font-bold ${
                    player.rank === 1
                      ? "text-yellow-500"
                      : player.rank === 2
                        ? "text-gray-400"
                        : player.rank === 3
                          ? "text-amber-600"
                          : "text-gray-400 dark:text-gray-500"
                  }`}
                >
                  {player.rank}
                  {player.rank === 1
                    ? "st"
                    : player.rank === 2
                      ? "nd"
                      : player.rank === 3
                        ? "rd"
                        : "th"}
                </span>
              </div>
              <div className="col-span-5 flex items-center gap-3">
                <Image
                  src={`https://mc-heads.net/avatar/${player.minecraft_uuid}/64.png`}
                  alt={player.minecraft_username ?? ""}
                  width={28}
                  height={28}
                  className="rounded bg-gray-200 dark:bg-gray-700"
                />
                <span className="font-bold text-sm text-gray-700 dark:text-gray-300 truncate">
                  {player.minecraft_username ?? "Unknown"}
                </span>
              </div>
              <div className="col-span-3 sm:col-span-1 text-center">
                <span className="font-bold text-orange-500 text-sm">
                  {player.crown_score}
                </span>
              </div>
              <div className="hidden sm:block col-span-1 text-center text-sm text-yellow-600 font-bold">
                {player.gold}
              </div>
              <div className="hidden sm:block col-span-1 text-center text-sm text-gray-400 font-bold">
                {player.silver}
              </div>
              <div className="hidden sm:block col-span-1 text-center text-sm text-amber-700 font-bold">
                {player.bronze}
              </div>
            </Link>
          ))}

          {players.length === 0 && (
            <div className="px-6 py-12 text-center text-gray-500 dark:text-gray-400 relative z-10">
              <p className="text-lg font-bold">No player data available</p>
              <p className="text-sm mt-1">Check back later</p>
            </div>
          )}
        </Squircle>
      </div>
    </div>
  );
}
