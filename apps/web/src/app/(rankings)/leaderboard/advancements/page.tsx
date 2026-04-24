import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import Squircle from "@/components/Squircle";
import AdvancementsCategoryTabs from "@/components/AdvancementsCategoryTabs";
import CompletionFireworks from "@/components/CompletionFireworks";
import {
  CATEGORY_LABELS,
  isValidCategory,
  type AdvancementCategory,
} from "@/lib/advancementCategories";

const PAGE_SIZE = 25;

interface Props {
  searchParams: Promise<{ category?: string; page?: string }>;
}

interface LeaderboardEntry {
  rank: number;
  uuid: string;
  username: string | null;
  completed: number;
}

interface AdvancementLeaderboardResponse {
  leaderboard: LeaderboardEntry[];
  total: number;
  totalAdvancements: number;
  category?: string;
  offset: number;
  limit: number;
}

async function fetchAdvancementLeaderboard(
  category: AdvancementCategory | null,
  offset: number,
  limit: number,
): Promise<AdvancementLeaderboardResponse | null> {
  const params = new URLSearchParams();
  if (category) params.set("category", category);
  params.set("limit", String(limit));
  params.set("offset", String(offset));
  const url = `https://api.crabcraft.net/advancements/leaderboard?${params.toString()}`;
  try {
    const res = await fetch(url, { next: { revalidate: 30 } });
    if (!res.ok) return null;
    return (await res.json()) as AdvancementLeaderboardResponse;
  } catch {
    return null;
  }
}

function parseCategory(raw: string | undefined): AdvancementCategory | null {
  if (!raw) return null;
  return isValidCategory(raw) ? raw : null;
}

function parsePage(raw: string | undefined): number {
  const n = Number.parseInt(raw ?? "1", 10);
  if (!Number.isFinite(n) || n < 1) return 1;
  return n;
}

function buildPageUrl(
  category: AdvancementCategory | null,
  page: number,
): string {
  const params = new URLSearchParams();
  if (category) params.set("category", category);
  if (page > 1) params.set("page", String(page));
  const qs = params.toString();
  return qs
    ? `/leaderboard/advancements?${qs}`
    : "/leaderboard/advancements";
}

const PODIUM = [
  // [0] = 1st (center on desktop)
  {
    gradient: "from-[#F59E0B] to-[#FBBF24]",
    label: "1",
    textSize: "text-[120px]",
    render: "relaxing",
    imgH: "h-[250px]",
    avatarSize: 72,
    nameSize: "text-xl",
    pctSize: "text-3xl",
    padding: "p-8",
    order: "order-first sm:order-none",
    mt: "",
  },
  // [1] = 2nd (left)
  {
    gradient: "from-[#9CA3AF] to-[#D1D5DB]",
    label: "2",
    textSize: "text-[100px]",
    render: "archer",
    imgH: "h-[200px]",
    avatarSize: 56,
    nameSize: "text-lg",
    pctSize: "text-2xl",
    padding: "p-6",
    order: "",
    mt: "sm:mt-8",
  },
  // [2] = 3rd (right)
  {
    gradient: "from-[#B45309] to-[#D97706]",
    label: "3",
    textSize: "text-[100px]",
    render: "lunging",
    imgH: "h-[200px]",
    avatarSize: 56,
    nameSize: "text-lg",
    pctSize: "text-2xl",
    padding: "p-6",
    order: "",
    mt: "sm:mt-8",
  },
];

const PODIUM_ORDER = [1, 0, 2]; // 2nd, 1st, 3rd visually on desktop

export async function generateMetadata({
  searchParams,
}: Props): Promise<Metadata> {
  const sp = await searchParams;
  const cat = parseCategory(sp.category);
  const title = cat
    ? `${CATEGORY_LABELS[cat]} Advancements Leaderboard`
    : "Advancements";
  return {
    title,
    description:
      "See which CrabCraft players have completed the most Minecraft advancements.",
  };
}

export default async function AdvancementsLeaderboardPage({
  searchParams,
}: Props) {
  const sp = await searchParams;
  const category = parseCategory(sp.category);
  const requestedPage = parsePage(sp.page);
  const offset = (requestedPage - 1) * PAGE_SIZE;
  const data = await fetchAdvancementLeaderboard(category, offset, PAGE_SIZE);

  const entries = data?.leaderboard ?? [];
  const total = data?.total ?? 0;
  const totalAdvancements = data?.totalAdvancements ?? 125;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const page = Math.min(requestedPage, totalPages);

  const isFirstPage = page === 1;
  const top3 = isFirstPage ? entries.slice(0, 3) : [];
  const rest = isFirstPage ? entries.slice(3) : entries;

  const pct = (entry: LeaderboardEntry): number =>
    totalAdvancements > 0 ? (entry.completed / totalAdvancements) * 100 : 0;

  return (
    <>
        <div className="text-center mb-10">
          <p className="text-gray-600 dark:text-gray-400 animate-in">
            {total} {total === 1 ? "player" : "players"} ranked by completed
            advancements
          </p>
        </div>

        <AdvancementsCategoryTabs active={category} />

        {/* Podium */}
        {top3.length === 3 && (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 lg:gap-6 mb-10 max-w-7xl mx-auto">
            {PODIUM_ORDER.map((idx) => {
              const player = top3[idx];
              const style = PODIUM[idx];
              const playerPct = pct(player);
              const isFull = player.completed === totalAdvancements;
              return (
                <div
                  key={player.uuid}
                  className={`${style.mt} ${style.order} relative animate-in`}
                  style={{ animationDelay: `${0.1 + idx * 0.05}s` }}
                >
                  {isFull && <CompletionFireworks />}
                  <Squircle
                    cornerRadius={32}
                    className={`card-hover overflow-hidden bg-gradient-to-br ${style.gradient}`}
                  >
                  <Link
                    href={`/stats/${player.uuid}`}
                    className={`block ${style.padding} relative cursor-pointer`}
                  >
                    <span
                      className={`hidden sm:block absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 ${style.textSize} font-bold text-white/10 z-0 select-none pointer-events-none font-mc`}
                    >
                      {style.label}
                    </span>
                    <div className="absolute bottom-0 right-0 pointer-events-none z-0 hidden sm:block opacity-30">
                      <Image
                        src={`https://starlightskins.lunareclipse.studio/render/${style.render}/${player.uuid}/full`}
                        alt=""
                        width={140}
                        height={280}
                        className={`${style.imgH} w-auto`}
                      />
                    </div>
                    <div className="relative z-10 flex flex-col items-start text-left gap-3">
                      <Image
                        src={`https://mc-heads.net/avatar/${player.uuid}/100.png`}
                        alt={player.username ?? ""}
                        width={style.avatarSize}
                        height={style.avatarSize}
                        className="rounded-lg bg-white/20"
                      />
                      <div>
                        <p className={`font-bold text-white ${style.nameSize}`}>
                          {player.username ?? "Unknown"}
                        </p>
                        <p
                          className={`font-mc text-white/90 ${style.pctSize} mt-1`}
                        >
                          {player.completed}
                          <span className="text-white/60 text-base">
                            /{totalAdvancements}
                          </span>
                        </p>
                        <div className="flex items-center gap-2 mt-2">
                          <div className="h-1.5 w-32 bg-black/20 rounded-full overflow-hidden">
                            <div
                              className={`h-full rounded-full ${isFull ? "holo-bg" : "bg-white"}`}
                              style={{ width: `${playerPct}%` }}
                            />
                          </div>
                          <span className="text-xs font-bold text-white/80">
                            {playerPct.toFixed(1)}%
                          </span>
                        </div>
                      </div>
                    </div>
                  </Link>
                  </Squircle>
                </div>
              );
            })}
          </div>
        )}

        {/* Standings table */}
        <Squircle
          cornerRadius={32}
          className="bg-paper-2 shadow-sm overflow-hidden relative animate-in max-w-6xl mx-auto"
          style={{ animationDelay: "0.25s" }}
        >
          <div className="hidden sm:grid grid-cols-12 gap-2 px-6 py-3 bg-paper/80 dark:bg-paper-2/80 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider border-b border-gray-200 dark:border-[#3d3028] relative z-10">
            <div className="col-span-1 text-center">Rank</div>
            <div className="col-span-4">Player</div>
            <div className="col-span-5">Progress</div>
            <div className="col-span-2 text-right">Completed</div>
          </div>

          {rest.map((player, i) => {
            const playerPct = pct(player);
            const isFull = player.completed === totalAdvancements;
            const isHigh = playerPct >= 90;
            const medalColor =
              player.rank === 1
                ? "text-yellow-500"
                : player.rank === 2
                  ? "text-gray-400"
                  : player.rank === 3
                    ? "text-amber-600"
                    : "text-gray-400 dark:text-gray-500";
            const suffix =
              player.rank === 1
                ? "st"
                : player.rank === 2
                  ? "nd"
                  : player.rank === 3
                    ? "rd"
                    : "th";
            return (
              <Link
                key={player.uuid}
                href={`/stats/${player.uuid}`}
                className={`grid grid-cols-12 gap-2 px-6 py-3 items-center hover:bg-orange-50/60 dark:hover:bg-[#2a221b] transition-colors relative z-10 cursor-pointer ${
                  i % 2 === 0
                    ? "bg-paper-2/80"
                    : "bg-paper/60 dark:bg-[#2a221b]/40"
                }`}
              >
                <div className="col-span-2 sm:col-span-1 flex items-center justify-center">
                  <span className={`text-sm font-bold ${medalColor}`}>
                    {player.rank}
                    {suffix}
                  </span>
                </div>
                <div className="col-span-7 sm:col-span-4 flex items-center gap-2 min-w-0">
                  <Image
                    src={`https://mc-heads.net/avatar/${player.uuid}/64.png`}
                    alt={player.username ?? ""}
                    width={28}
                    height={28}
                    className="rounded bg-gray-200 dark:bg-gray-700 shrink-0"
                  />
                  <span className="font-bold text-sm text-gray-700 dark:text-gray-300 truncate">
                    {player.username ?? "Unknown"}
                  </span>
                </div>
                <div className="hidden sm:flex col-span-5 items-center gap-2">
                  <div className="flex-1 h-2 bg-gray-200/70 dark:bg-[#1a1412] rounded-full overflow-hidden shadow-inner">
                    {isFull ? (
                      <div
                        className="h-full rounded-full holo-bg shadow-[0_0_10px_rgba(255,182,234,0.6)]"
                        style={{ width: `${playerPct}%` }}
                      />
                    ) : (
                      <div
                        className="h-full rounded-full transition-all duration-500"
                        style={{
                          width: `${playerPct}%`,
                          background:
                            "linear-gradient(90deg, #fdba74, #fb923c 50%, #f97316)",
                          boxShadow: isHigh
                            ? "0 0 8px rgba(249, 115, 22, 0.45)"
                            : "none",
                        }}
                      />
                    )}
                  </div>
                  <span className="text-xs font-bold text-gray-500 dark:text-gray-400 tabular-nums w-10 text-right">
                    {playerPct.toFixed(0)}%
                  </span>
                </div>
                <div className="col-span-3 sm:col-span-2 text-right">
                  <span className="font-bold text-orange-500 text-sm tabular-nums">
                    {player.completed}
                    <span className="text-gray-400 dark:text-gray-500 font-normal">
                      /{totalAdvancements}
                    </span>
                  </span>
                </div>
              </Link>
            );
          })}

          {entries.length === 0 && (
            <div className="px-6 py-12 text-center text-gray-500 dark:text-gray-400 relative z-10">
              <p className="text-lg font-bold">No player data available</p>
              <p className="text-sm mt-1">
                {data === null
                  ? "Could not reach the stats server"
                  : "Check back after players have earned some advancements"}
              </p>
            </div>
          )}
        </Squircle>

        {/* Pagination */}
        {entries.length > 0 && totalPages > 1 && (
          <div
            className="mt-6 flex items-center justify-center gap-3 animate-in"
            style={{ animationDelay: "0.3s" }}
          >
            {page > 1 ? (
              <Link
                href={buildPageUrl(category, page - 1)}
                className="px-4 py-2 text-sm font-bold rounded-xl bg-paper-2 hover:bg-orange-50/60 dark:hover:bg-[#2a221b] text-gray-700 dark:text-gray-300 transition-colors"
              >
                &larr; Prev
              </Link>
            ) : (
              <span className="px-4 py-2 text-sm font-bold rounded-xl bg-paper-2 text-gray-400 dark:text-gray-600 opacity-50 cursor-not-allowed">
                &larr; Prev
              </span>
            )}
            <span className="text-sm text-gray-600 dark:text-gray-400 font-medium px-2">
              Page {page} of {totalPages}
            </span>
            {page < totalPages ? (
              <Link
                href={buildPageUrl(category, page + 1)}
                className="px-4 py-2 text-sm font-bold rounded-xl bg-paper-2 hover:bg-orange-50/60 dark:hover:bg-[#2a221b] text-gray-700 dark:text-gray-300 transition-colors"
              >
                Next &rarr;
              </Link>
            ) : (
              <span className="px-4 py-2 text-sm font-bold rounded-xl bg-paper-2 text-gray-400 dark:text-gray-600 opacity-50 cursor-not-allowed">
                Next &rarr;
              </span>
            )}
          </div>
        )}

        <div
          className="mt-8 text-center animate-in"
          style={{ animationDelay: "0.35s" }}
        >
          <Link
            href="/awards"
            className="text-sm text-gray-500 dark:text-gray-400 hover:text-orange-500 transition-colors"
          >
            Browse all awards &rarr;
          </Link>
        </div>
    </>
  );
}
