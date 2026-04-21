"use client";

import { useState, useMemo } from "react";
import Link from "next/link";
import Image from "next/image";
import { Search } from "lucide-react";
import Squircle from "@/components/Squircle";
import { formatValue, type Units } from "@/lib/formatValue";
import { categorise, getTitle, getDesc } from "@/lib/categories";
import { AWARDS } from "@crabcraft/shared/awards";

type StatEntry = { rank?: number; value: number };
type StatsData = Record<string, StatEntry>;

export default function PlayerDetailedStats({
  stats,
  localization,
  awardUnits,
}: {
  stats: StatsData;
  localization?: Record<string, string> | null;
  awardUnits?: Units;
}) {
  const loc = (localization ?? null) as Record<string, string> | null;
  const units = awardUnits ?? null;

  // Convert stats Record to array, filter zeros, categorise
  const statItems = Object.entries(stats)
    .filter(([, entry]) => entry.value > 0)
    .map(([key, entry]) => ({ key, entry }));
  const buckets = categorise(statItems);
  // Sort each bucket by value descending
  for (const items of Object.values(buckets)) {
    items.sort((a, b) => b.entry.value - a.entry.value);
  }
  const tabs = Object.keys(buckets);
  const [activeTab, setActiveTab] = useState(tabs[0] || "");
  const [search, setSearch] = useState("");

  const resolveTitle = (key: string) =>
    AWARDS[key]?.title ?? getTitle(key, loc);
  const resolveDesc = (key: string) =>
    AWARDS[key]?.desc || getDesc(key, loc);

  const searchResults = useMemo(() => {
    if (search.length < 2) return null;
    const q = search.toLowerCase();
    return statItems.filter(({ key }) => {
      const title = resolveTitle(key);
      const desc = resolveDesc(key);
      return title.toLowerCase().includes(q) || desc?.toLowerCase().includes(q) || key.includes(q);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search, statItems, loc]);

  if (tabs.length === 0) return null;

  const items = searchResults ?? buckets[activeTab] ?? [];

  return (
    <div className="mt-6 animate-in" style={{ animationDelay: "0.3s" }}>
      {/* Search */}
      <div className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-gray-100/80 dark:bg-white/5 mb-4 max-w-md mx-auto">
        <Search className="w-4 h-4 text-gray-400 shrink-0" />
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search awards..."
          className="flex-1 text-sm bg-transparent text-gray-800 dark:text-gray-200 placeholder-gray-400 dark:placeholder-gray-500 outline-none"
        />
      </div>

      {/* Tab bar — hidden when searching */}
      {!searchResults && (
      <div className="flex gap-2 pb-2 mb-4 justify-center flex-wrap">
        {tabs.map((tab) => (
          <Squircle
            key={tab}
            cornerRadius={14}
            onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 text-sm font-bold whitespace-nowrap transition-colors cursor-pointer ${
              activeTab === tab
                ? "bg-orange-500 text-white"
                : "bg-gray-200 dark:bg-[#2a221b] text-gray-600 dark:text-gray-400 hover:bg-gray-300 dark:hover:bg-[#3d3028]"
            }`}
          >
            {tab}
            <span className="ml-1.5 text-xs opacity-70">{buckets[tab].length}</span>
          </Squircle>
        ))}
      </div>
      )}

      {/* Stats table */}
      <Squircle cornerRadius={32} className="bg-paper-2 overflow-hidden">
        <div className="grid grid-cols-12 gap-4 px-5 py-3 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider border-b border-gray-200 dark:border-[#3d3028]">
          <div className="col-span-2 sm:col-span-1">Rank</div>
          <div className="col-span-6 sm:col-span-7">Award</div>
          <div className="col-span-4 text-right">Value</div>
        </div>

        {items.map(({ key, entry }, i) => {
          const title = resolveTitle(key);
          const desc = resolveDesc(key);

          return (
            <Link
              key={key}
              href={`/awards/${key}`}
              className={`grid grid-cols-12 gap-4 px-5 py-3 items-center text-sm cursor-pointer transition-colors hover:bg-orange-50/60 dark:hover:bg-[#2a221b] ${
                i % 2 === 0
                  ? "bg-paper-2"
                  : "bg-paper/60 dark:bg-[#2a221b]/40"
              }`}
            >
              <div className="col-span-2 sm:col-span-1">
                {entry.rank ? (
                  <span className={`text-sm font-bold ${entry.rank === 1 ? "text-yellow-500" : entry.rank === 2 ? "text-gray-400" : entry.rank === 3 ? "text-amber-600" : "text-gray-400 dark:text-gray-500"}`}>
                    {entry.rank}{entry.rank === 1 ? "st" : entry.rank === 2 ? "nd" : entry.rank === 3 ? "rd" : "th"}
                  </span>
                ) : (
                  <span className="text-xs text-gray-400">—</span>
                )}
              </div>
              <div className="col-span-6 sm:col-span-7 min-w-0 flex items-center gap-3">
                <Image
                  src={AWARDS[key]?.icon ?? `/awards/icons/${key}.png`}
                  alt=""
                  width={32}
                  height={32}
                  unoptimized
                  className="shrink-0 pixelated hidden sm:block"
                />
                <div className="min-w-0">
                  <p className="text-gray-800 dark:text-gray-200 font-bold truncate">
                    {title}
                  </p>
                  {desc && (
                    <p className="text-xs text-gray-400 dark:text-gray-500 truncate">
                      {desc}
                    </p>
                  )}
                </div>
              </div>
              <div className="col-span-4 text-right font-bold text-gray-800 dark:text-gray-200">
                {formatValue(entry.value, key, units)}
              </div>
            </Link>
          );
        })}
        {items.length === 0 && searchResults && (
          <div className="px-6 py-12 text-center text-gray-500 dark:text-gray-400">
            <p className="text-sm">No awards found for &ldquo;{search}&rdquo;</p>
          </div>
        )}
      </Squircle>
    </div>
  );
}
