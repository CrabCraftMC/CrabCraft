import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import config from "@/data/site-config.json";
import CopyIPCard from "@/components/CopyIPCard";
import CountdownBanner from "@/components/CountdownBanner";
import Squircle from "@/components/Squircle";
import { getOverviewStats } from "@/lib/queries";

export const metadata: Metadata = {
  description:
    "CrabCraft is a whitelisted Minecraft survival server. Apply to join, explore the live map, and meet our community.",
  openGraph: {
    title: "CrabCraft",
    description:
      "A whitelisted Minecraft survival server. Apply to join and start your adventure.",
  },
};

export default async function HomePage() {
  let topPlayers: { uuid: string; name: string; points: number }[] = [];
  let whitelistedPlayers = 0;
  let onlinePlayers = 0;
  let onlinePlayerList: { name: string; uuid: string; nickname_raw?: string }[] = [];

  const playerCountPromise = getOverviewStats()
    .then((stats) => stats.playerCount)
    .catch(() => 0);
  const playersPromise = fetch("https://api.crabcraft.net/players", {
    signal: AbortSignal.timeout(5000),
    cache: "no-store",
  }).catch(() => null);
  const crownsPromise = fetch("https://api.crabcraft.net/awards/crowns?limit=5", {
    signal: AbortSignal.timeout(5000),
    next: { revalidate: 30 },
  }).catch(() => null);

  const [whitelistedCount, playersRes, crownsRes] = await Promise.all([
    playerCountPromise,
    playersPromise,
    crownsPromise,
  ]);

  whitelistedPlayers = whitelistedCount;

  if (crownsRes?.ok) {
    try {
      const data = await crownsRes.json();
      topPlayers = (data.leaderboard ?? []).map((p: any) => ({
        uuid: p.uuid,
        name: p.username ?? "Unknown",
        points: p.crown_score,
      }));
    } catch {}
  }

  if (playersRes?.ok) {
    try {
      const data = await playersRes.json();
      onlinePlayers = data.count ?? 0;
      onlinePlayerList = (data.players || []).map((p: any) => ({
        name: p.username,
        uuid: p.uuid,
        nickname_raw: p.nickname_raw,
      }));
    } catch (e) {
      console.error("Failed to fetch player list:", e);
    }
  }

  return (
    <div className="min-h-screen relative">
      <CountdownBanner />

      <section className="relative gap-8 mt-16">
        <div className="container mx-auto px-4 relative grid grid-cols-1 lg:grid-cols-8 gap-6 lg:gap-8 h-auto lg:h-[570px]">
          {(config.home.hero as any[]).map((item: any, index: number) => (
            <Squircle
              cornerRadius={32}
              key={index}
              className={`${item.colSpan} card-hover animate-in p-6 sm:p-8 pb-8 sm:pb-10 flex flex-col justify-between overflow-hidden relative bg-gradient-to-br ${item.bgGradient} min-h-[220px]`}
              style={{ animationDelay: `${index * 0.1}s` }}
            >
              <span
                className={`hidden sm:block absolute top-1/2 left-1/2 -translate-x-1/2 ${item.bgTextPos} ${item.bgTextSize} font-bold text-transparent bg-clip-text bg-gradient-to-b from-[#eeeeee12] to-[#d9d9d9] z-0 select-none pointer-events-none`}
              >
                {item.title}
              </span>
              <div className="text-white space-y-4 relative z-10">
                <h2 className="text-2xl sm:text-3xl md:text-4xl font-bold">
                  {item.subtitle}
                </h2>
                <p className="text-base sm:text-lg leading-relaxed">
                  {item.description}
                </p>
              </div>
              <div
                className={`hidden sm:flex items-end absolute ${index === 0 ? "-bottom-2" : "-bottom-4"} right-0 left-0 pointer-events-none z-10`}
              >
                <img
                  src={item.image}
                  alt={item.subtitle}
                  width={400}
                  height={400}
                  className={`${item.imageScale} w-auto h-auto origin-bottom`}
                />
              </div>
              <a
                href={item.url}
                target="_blank"
                rel="noopener noreferrer"
                className={`relative z-10 block cursor-pointer ${item.buttonBg} text-white font-bold py-3 px-6 rounded-full w-full text-center text-xl shadow-lg transition-transform hover:scale-105`}
              >
                {item.buttonText}
              </a>
            </Squircle>
          ))}
        </div>
      </section>

      <section className="mt-16 relative container mx-auto lg:px-8">
        <div className="relative">
          <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-6 relative z-10">
            <div
              className="relative w-full lg:w-1/2 text-center lg:text-left animate-in"
              style={{ animationDelay: "0.3s" }}
            >
              <h2 className="text-3xl font-bold text-orange-500 font-mc">
                Top Players
              </h2>
              <p className="mt-2 text-base text-gray-600 dark:text-gray-400">
                Best players on the server
              </p>
            </div>
            <div className="flex flex-wrap items-center justify-center lg:justify-end gap-4 lg:gap-6 flex-shrink-0 max-w-full">
              {topPlayers.map((player, i) => (
                <Link
                  key={player.uuid}
                  href={`/stats/${player.uuid}`}
                  className={`flex flex-col items-center gap-1 animate-in cursor-pointer hover:scale-110 transition-transform ${player.name.length > 12 ? "w-32 lg:w-36" : player.name.length > 8 ? "w-28 lg:w-32" : "w-24 lg:w-28"}`}
                  style={{ animationDelay: `${0.35 + i * 0.08}s` }}
                >
                  <Image
                    src={`https://mc-heads.net/avatar/${player.uuid}/100.png`}
                    alt={player.name}
                    width={60}
                    height={60}
                    sizes="60px"
                    className="bg-gray-300 dark:bg-gray-700 rounded-md"
                  />
                  <div className="text-center">
                    <p className="font-bold text-sm lg:text-base text-gray-800 dark:text-gray-200 truncate">
                      {player.name}
                    </p>
                    <p className="text-xs lg:text-sm text-orange-400 truncate">
                      {player.points} pts
                    </p>
                  </div>
                </Link>
              ))}
              <Link
                href="/leaderboard"
                className="animate-in w-full sm:w-auto text-center"
                style={{ animationDelay: "0.7s" }}
              >
                <button className="bg-orange-500 hover:bg-orange-600 text-white font-bold py-2 px-5 rounded-2xl text-sm transition-colors cursor-pointer active:scale-95">
                  View All
                </button>
              </Link>
            </div>
          </div>
        </div>
      </section>

      <section className="mt-16 relative">
        <div className="container mx-auto px-4">
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 lg:gap-8">
            <div className="flex flex-col gap-6 lg:gap-8">
              <Squircle
                cornerRadius={32}
                className="card-hover animate-in p-6 lg:p-8 relative overflow-hidden bg-gradient-to-br from-[#8B5CF6] to-[#A78BFA] min-h-[180px]"
                style={{ animationDelay: "0.4s" }}
              >
                <span className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[60px] sm:text-[70px] lg:text-[90px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap hidden sm:block">
                  EST.
                </span>
                <div className="relative z-10 flex flex-col justify-between h-full">
                  <h2 className="text-xl lg:text-2xl font-bold text-white">
                    Total Seasons
                  </h2>
                  <div className="mt-4">
                    <p className="text-3xl lg:text-4xl font-bold text-white font-mc">
                      6 Seasons
                    </p>
                    <p className="text-white/70 text-sm mt-1">Est. 2024</p>
                  </div>
                </div>
              </Squircle>

              <Squircle
                cornerRadius={32}
                className="card-hover animate-in p-6 lg:p-8 relative overflow-hidden bg-gradient-to-br from-[#06B6D4] to-[#22D3EE] min-h-[180px]"
                style={{ animationDelay: "0.5s" }}
              >
                <span className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[60px] sm:text-[70px] lg:text-[90px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap hidden sm:block">
                  WL
                </span>
                <div className="relative z-10 flex flex-col justify-between h-full">
                  <h2 className="text-xl lg:text-2xl font-bold text-white">
                    Whitelisted Players
                  </h2>
                  <div className="mt-4">
                    <p className="text-3xl lg:text-4xl font-bold text-white font-mc">
                      {whitelistedPlayers}
                    </p>
                    <p className="text-white/70 text-sm mt-1">and counting</p>
                  </div>
                </div>
              </Squircle>
            </div>

            <CopyIPCard
              onlinePlayers={onlinePlayers}
              onlinePlayerList={onlinePlayerList}
            />
          </div>
        </div>
      </section>

      <section className="mt-16 pb-8 relative">
        <div className="container mx-auto px-4">
          <div
            className="mb-8 animate-in"
            style={{ animationDelay: "0.55s" }}
          >
            <h2 className="text-3xl lg:text-4xl font-bold text-orange-500 mb-2 font-mc">
              Server Rules
            </h2>
            <p className="text-base lg:text-lg text-gray-600 dark:text-gray-400">
              Three simple rules to follow
            </p>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-6 lg:gap-8">
            <Squircle
              cornerRadius={32}
              className="card-hover animate-in p-6 lg:p-8 relative overflow-hidden bg-gradient-to-br from-[#E11D48] to-[#FB7185] min-h-[250px]"
              style={{ animationDelay: "0.6s" }}
            >
              <span className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[50px] sm:text-[60px] lg:text-[80px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap hidden sm:block">
                RESPECT
              </span>
              <div className="relative z-10 flex flex-col justify-between h-full">
                <h2 className="text-2xl lg:text-3xl font-bold text-white">
                  Respect
                </h2>
                <p className="text-white/90 text-sm lg:text-base mt-4 leading-relaxed">
                  Treat others with respect at all times. No griefing, stealing,
                  harassment, bullying, or discrimination. If someone asks you to
                  stop, you must stop.
                </p>
              </div>
            </Squircle>

            <Squircle
              cornerRadius={32}
              className="card-hover animate-in p-6 lg:p-8 relative overflow-hidden bg-gradient-to-br from-[#2563EB] to-[#60A5FA] min-h-[250px]"
              style={{ animationDelay: "0.7s" }}
            >
              <span className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[40px] sm:text-[50px] lg:text-[70px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap hidden sm:block">
                INTEGRITY
              </span>
              <div className="relative z-10 flex flex-col justify-between h-full">
                <h2 className="text-2xl lg:text-3xl font-bold text-white">
                  Integrity
                </h2>
                <p className="text-white/90 text-sm lg:text-base mt-4 leading-relaxed">
                  Play fairly and honestly. Cheating, hacking, exploiting,
                  duping, or abusing bugs or unintended game mechanics is
                  strictly prohibited.
                </p>
              </div>
            </Squircle>

            <Squircle
              cornerRadius={32}
              className="card-hover animate-in p-6 lg:p-8 relative overflow-hidden bg-gradient-to-br from-[#16A34A] to-[#4ADE80] min-h-[250px]"
              style={{ animationDelay: "0.8s" }}
            >
              <span className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[35px] sm:text-[45px] lg:text-[65px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap hidden sm:block">
                COMMUNITY
              </span>
              <div className="relative z-10 flex flex-col justify-between h-full">
                <h2 className="text-2xl lg:text-3xl font-bold text-white">
                  Community
                </h2>
                <p className="text-white/90 text-sm lg:text-base mt-4 leading-relaxed">
                  Contribute to a positive and friendly server environment.
                  Respect other players&apos; builds and shared areas, help
                  others when you can, and do not disrupt others&apos; gameplay.
                </p>
              </div>
            </Squircle>
          </div>
        </div>
      </section>

      <section className="mt-16 pb-16">
        <div className="container mx-auto px-4">
          <a
            href="https://discord.crabcraft.net"
            target="_blank"
            rel="noopener noreferrer"
            className="block"
          >
            <Squircle cornerRadius={32} className="card-hover animate-in p-8 lg:p-12 overflow-hidden bg-gradient-to-r from-[#F97316] to-[#FB923C] cursor-pointer" style={{ animationDelay: "0.9s" }}>
              <div className="relative z-10 flex flex-col lg:flex-row items-center justify-between gap-6">
                <div>
                  <p className="text-white/70 text-sm">
                    C&apos;mon... you&apos;ve scrolled this far
                  </p>
                  <h2 className="text-2xl lg:text-4xl font-bold text-white mt-4 font-mc">
                    Start your survival adventure now
                  </h2>
                </div>
                <div className="bg-white/20 backdrop-blur-sm border border-white/30 text-white font-bold py-3 px-8 rounded-full text-lg shadow-lg transition-transform hover:scale-105">
                  Apply Now
                </div>
              </div>
            </Squircle>
          </a>
        </div>
      </section>
    </div>
  );
}
