import type { Metadata } from "next";
import Link from "next/link";
import { categorise, getTitle, getDesc } from "@/lib/categories";
import AwardsTabs from "@/components/AwardsTabs";
import { fetchGzipJson } from "@/lib/fetchGzip";

const BASE = "https://map.crabcraft.net/stats";
const HEADERS = { Referer: "https://crabcraft.net" };

function getCachePrefix(uuid: string): string {
  return uuid.replace(/-/g, "").slice(0, 2);
}

export const metadata: Metadata = {
  title: "Awards",
  description: "Browse all CrabCraft awards and see who holds the #1 spot.",
};

export default async function AwardsPage() {
  let awards: Record<
    string,
    { unit: string; best: { uuid: string; value: number } }
  > = {};
  let loc: Record<string, string> | null = null;
  let awardUnits: Record<string, string> | null = null;

  // Fetch summary + localization in parallel
  const [summary, locRes] = await Promise.all([
    fetchGzipJson<any>(`${BASE}/data/summary.json.gz`, { headers: HEADERS }),
    fetch(`${BASE}/localization/en.json`, {
      headers: HEADERS,
      next: { revalidate: 3600 },
    }).catch(() => null),
  ]);

  if (summary) {
    awards = summary.awards || {};
    awardUnits = {};
    for (const [k, v] of Object.entries(awards) as [string, any][]) {
      if (v.unit) awardUnits[k] = v.unit;
    }
  }

  if (locRes?.ok) {
    loc = await locRes.json();
  }

  // Fetch player names for #1 holders
  const bestUuids = Object.values(awards)
    .filter((a) => a.best?.uuid)
    .map((a) => a.best.uuid);
  const prefixes = [...new Set(bestUuids.map(getCachePrefix))];
  const cacheResults = await Promise.all(
    prefixes.map((prefix) =>
      fetch(`${BASE}/data/playercache/${prefix}.json`, {
        headers: HEADERS,
        next: { revalidate: 3600 },
      })
        .then((r) => (r.ok ? r.json() : []))
        .catch(() => [])
    )
  );

  const nameMap = new Map<string, string>();
  for (const batch of cacheResults) {
    for (const entry of batch as { uuid: string; name: string }[]) {
      nameMap.set(entry.uuid, entry.name);
    }
  }

  // Build award entries and categorise
  const awardItems = Object.entries(awards).map(([key, award]) => ({
    key,
    title: getTitle(key, loc),
    desc: getDesc(key, loc),
    bestName: award.best?.uuid ? (nameMap.get(award.best.uuid) ?? null) : null,
    bestUuid: award.best?.uuid ?? null,
    bestValue: award.best?.value ?? 0,
  }));

  const buckets = categorise(awardItems);

  // Sort each bucket alphabetically by title
  for (const items of Object.values(buckets)) {
    items.sort((a, b) => a.title.localeCompare(b.title));
  }

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-4xl">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Awards
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            {Object.keys(awards).length} awards to compete for
          </p>
        </div>

        <AwardsTabs buckets={buckets} units={awardUnits} />

        <div
          className="mt-8 text-center animate-in"
          style={{ animationDelay: "0.2s" }}
        >
          <Link
            href="/leaderboard"
            className="text-sm text-gray-500 dark:text-gray-400 hover:text-orange-500 transition-colors"
          >
            &larr; Back to Leaderboard
          </Link>
        </div>
      </div>
    </div>
  );
}
