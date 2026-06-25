"use client";

import { useState, useMemo } from "react";
import Link from "next/link";
import { Search } from "lucide-react";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import { formatValue, type Units } from "@/lib/formatValue";
import { categorise, getTitle, getDesc } from "@/lib/categories";

type StatEntry = { rank?: number; value: number };
type StatsData = Record<string, StatEntry>;

type AwardMeta = { title: string; description: string; icon: string };

export default function PlayerDetailedStats({
  stats,
  awardsById,
  localization,
  awardUnits,
}: {
  stats: StatsData;
  /** Award metadata keyed by award id, provided by the server
   *  component from getAwardDefinitions(). */
  awardsById?: Record<string, AwardMeta>;
  localization?: Record<string, string> | null;
  awardUnits?: Units;
}) {
  const loc = (localization ?? null) as Record<string, string> | null;
  const units = awardUnits ?? null;
  const metaFor = awardsById ?? {};

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
  const ALL_TAB = "__all__";
  const [activeTab, setActiveTab] = useState(ALL_TAB);
  const [search, setSearch] = useState("");

  const resolveTitle = (key: string) =>
    metaFor[key]?.title ?? getTitle(key, loc);
  const resolveDesc = (key: string) =>
    metaFor[key]?.description || getDesc(key, loc);

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

  if (tabs.length === 0) return (
    <div className="animate-in" style={{ animationDelay: "0.3s" }}>
      <div className="flex items-center justify-between mb-3">
        <p className="text-[11px] uppercase tracking-wider text-gray-500 dark:text-gray-500 pl-1">
          Awards & Statistics
        </p>
      </div>
      <Squircle cornerRadius={32} className="bg-paper-2">
        <div className="px-6 py-12 text-center text-gray-500 dark:text-gray-400">
          <p className="text-sm">No awards data for this season yet</p>
        </div>
      </Squircle>
    </div>
  );

  const allItems = useMemo(() => {
    const all = Object.values(buckets).flat();
    all.sort((a, b) => b.entry.value - a.entry.value);
    return all;
  }, [buckets]);

  const items = searchResults ?? (activeTab === ALL_TAB ? allItems : buckets[activeTab] ?? []);

  return (
    <div className="animate-in" style={{ animationDelay: "0.3s" }}>
      {/* Header: label + search */}
      <div className="flex items-center justify-between mb-3">
        <p className="text-[11px] uppercase tracking-wider text-gray-500 dark:text-gray-500 pl-1">
          Awards & Statistics
        </p>
        <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-gray-100/80 dark:bg-white/5 border border-gray-200 dark:border-line w-48">
          <Search className="w-3.5 h-3.5 text-gray-400 shrink-0" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search awards..."
            className="flex-1 text-xs bg-transparent text-gray-800 dark:text-gray-200 placeholder-gray-400 dark:placeholder-gray-500 outline-none"
          />
        </div>
      </div>

      {/* Tab bar — hidden when searching */}
      {!searchResults && (
      <div className="flex gap-1.5 pb-2 mb-4 flex-wrap">
        <Squircle
          cornerRadius={10}
          onClick={() => setActiveTab(ALL_TAB)}
          className={`px-3 py-1.5 text-[12px] font-semibold whitespace-nowrap transition-colors cursor-pointer ${
            activeTab === ALL_TAB
              ? "bg-orange-500 text-white"
              : "bg-gray-200 dark:bg-[#2a221b] text-gray-600 dark:text-gray-400 hover:bg-gray-300 dark:hover:bg-[#3d3028]"
          }`}
        >
          All
        </Squircle>
        {tabs.map((tab) => (
          <Squircle
            key={tab}
            cornerRadius={10}
            onClick={() => setActiveTab(tab)}
            className={`px-3 py-1.5 text-[12px] font-semibold whitespace-nowrap transition-colors cursor-pointer ${
              activeTab === tab
                ? "bg-orange-500 text-white"
                : "bg-gray-200 dark:bg-[#2a221b] text-gray-600 dark:text-gray-400 hover:bg-gray-300 dark:hover:bg-[#3d3028]"
            }`}
          >
            {tab}
            <span className="ml-1 text-[10px] opacity-70">{buckets[tab].length}</span>
          </Squircle>
        ))}
      </div>
      )}

      {/* Stats table */}
      <Squircle cornerRadius={32} className="bg-paper-2">
        <div className="max-h-[70vh] lg:max-h-none overflow-y-auto themed-scrollbar">
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
                <PixelIcon
                  src={metaFor[key]?.icon ?? `/awards/icons/${key}.png`}
                  size={32}
                  className="hidden sm:inline-flex"
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
        </div>
      </Squircle>
    </div>
  );
}
