"use client";

import { useState, useMemo } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Search } from "lucide-react";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import { formatValue, type Units } from "@/lib/formatValue";

interface AwardEntry {
  key: string;
  title: string;
  desc: string | null;
  bestName: string | null;
  bestUuid: string | null;
  bestValue: number;
}

interface AwardsTabsProps {
  buckets: Record<string, AwardEntry[]>;
  units: Units;
}

export default function AwardsTabs({ buckets, units }: AwardsTabsProps) {
  const tabs = Object.keys(buckets);
  const [activeTab, setActiveTab] = useState(tabs[0] || "");
  const [search, setSearch] = useState("");
  const router = useRouter();

  const allItems = useMemo(
    () => Object.values(buckets).flat(),
    [buckets]
  );

  const searchResults = useMemo(() => {
    if (search.length < 2) return null;
    const q = search.toLowerCase();
    return allItems.filter(
      (a) =>
        a.title.toLowerCase().includes(q) ||
        a.desc?.toLowerCase().includes(q) ||
        a.key.includes(q)
    );
  }, [search, allItems]);

  if (tabs.length === 0) return null;

  const items = searchResults ?? buckets[activeTab] ?? [];

  return (
    <>
      {/* Search */}
      <div className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-gray-100/80 dark:bg-white/5 mb-4 max-w-md mx-auto">
        <Search className="w-4 h-4 text-gray-400 shrink-0" />
        <input
          type="search"
          aria-label="Search awards"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search awards..."
          className="flex-1 text-sm bg-transparent text-gray-800 dark:text-gray-200 placeholder-gray-400 dark:placeholder-gray-500 outline-none"
        />
      </div>

      {/* Tab bar — hidden when searching */}
      {!searchResults && (
      <div className="flex gap-2 pb-2 mb-6 justify-center flex-wrap">
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
            <span className="ml-1.5 text-xs opacity-70">
              {buckets[tab].length}
            </span>
          </Squircle>
        ))}
      </div>
      )}

      {/* Awards table */}
      <Squircle
        cornerRadius={32}
        className="bg-paper-2 overflow-hidden"
      >
        <div className="grid grid-cols-[minmax(0,1fr)_6rem] sm:grid-cols-[minmax(0,1fr)_10rem_7rem] md:grid-cols-[minmax(0,1fr)_14rem_8rem] items-center gap-4 px-5 py-3 text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider border-b border-gray-200 dark:border-[#3d3028]">
          <span>Award</span>
          <span className="hidden sm:block text-right">Player</span>
          <span className="text-right">Value</span>
        </div>
        {items.map((award, i) => (
          <button
            type="button"
            key={award.key}
            onClick={() => router.push(`/awards/${award.key}`)}
            className={`grid grid-cols-[minmax(0,1fr)_6rem] sm:grid-cols-[minmax(0,1fr)_10rem_7rem] md:grid-cols-[minmax(0,1fr)_14rem_8rem] items-center gap-4 px-5 py-3 cursor-pointer transition-colors hover:bg-orange-50/60 dark:hover:bg-[#2a221b] w-full text-left ${
              i % 2 === 0
                ? "bg-paper-2"
                : "bg-paper/60 dark:bg-[#2a221b]/40"
            }`}
          >
            <div className="min-w-0 flex items-center gap-3">
              <PixelIcon
                src={`/awards/icons/${award.key}.png`}
                size={32}
                className="hidden sm:inline-flex"
              />
              <div className="min-w-0">
                <p className="text-sm font-bold text-gray-800 dark:text-gray-200 truncate">
                  {award.title}
                </p>
                {award.desc && (
                  <p className="text-xs text-gray-400 dark:text-gray-500 truncate">
                    {award.desc}
                  </p>
                )}
              </div>
            </div>
            {award.bestUuid && (
              <Link
                href={`/stats/${award.bestUuid}`}
                onClick={(e) => e.stopPropagation()}
                className="hidden sm:flex min-w-0 items-center justify-end gap-2 hover:text-orange-500 transition-colors"
              >
                <PixelIcon
                  src={`https://mc-heads.net/avatar/${award.bestUuid}/32.png`}
                  alt={award.bestName || ""}
                  size={24}
                  imgClassName="rounded"
                />
                <span className="min-w-0 truncate text-xs font-bold text-gray-600 dark:text-gray-400 hover:text-orange-500 md:overflow-visible md:text-clip">
                  {award.bestName}
                </span>
              </Link>
            )}
            {!award.bestUuid && (
              <span className="hidden sm:block text-right text-xs font-bold text-gray-400 dark:text-gray-500">
                Unclaimed
              </span>
            )}
            <div className="min-w-0 text-right">
              <span className="block truncate text-sm font-bold text-orange-500">
                {formatValue(award.bestValue, award.key, units)}
              </span>
              {award.bestUuid && (
                <Link
                  href={`/stats/${award.bestUuid}`}
                  onClick={(e) => e.stopPropagation()}
                  className="flex sm:hidden items-center gap-1.5 justify-end mt-1 hover:text-orange-500 transition-colors"
                >
                  <PixelIcon
                    src={`https://mc-heads.net/avatar/${award.bestUuid}/32.png`}
                    alt={award.bestName || ""}
                    size={16}
                    imgClassName="rounded"
                  />
                  <span className="text-[10px] font-bold text-gray-500 dark:text-gray-400 hover:text-orange-500">
                    {award.bestName}
                  </span>
                </Link>
              )}
              {!award.bestUuid && (
                <span className="flex sm:hidden justify-end mt-1 text-[10px] font-bold text-gray-400 dark:text-gray-500">
                  Unclaimed
                </span>
              )}
            </div>
          </button>
        ))}
        {items.length === 0 && searchResults && (
          <div className="px-6 py-12 text-center text-gray-500 dark:text-gray-400">
            <p className="text-sm">No awards found for &ldquo;{search}&rdquo;</p>
          </div>
        )}
      </Squircle>
    </>
  );
}
