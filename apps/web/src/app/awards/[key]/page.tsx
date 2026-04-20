import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";
import Squircle from "@/components/Squircle";
import { formatValue } from "@/lib/formatValue";
import { gunzipSync } from "zlib";

const BASE = "https://map.crabcraft.net/stats/data";
const HEADERS = { Referer: "https://crabcraft.net" };

function getCachePrefix(uuid: string): string {
  return uuid.replace(/-/g, "").slice(0, 2);
}

interface Props {
  params: Promise<{ key: string }>;
}

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { key } = await params;

  let title = key.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
  try {
    const locRes = await fetch(`${BASE.replace("/data", "")}/localization/en.json`, {
      headers: HEADERS,
      next: { revalidate: 3600 },
    });
    if (locRes.ok) {
      const loc = await locRes.json();
      if (loc[`award.${key}.title`]) title = loc[`award.${key}.title`];
    }
  } catch {}

  return {
    title: `${title} Leaderboard`,
    description: `View the ${title} award leaderboard on CrabCraft.`,
  };
}

export default async function AwardLeaderboardPage({ params }: Props) {
  const { key } = await params;

  // Fetch rankings, localization, and award units in parallel
  const [rankingsRes, locRes, summaryRes] = await Promise.all([
    fetch(`${BASE}/rankings/${key}.json`, {
      headers: HEADERS,
      next: { revalidate: 60 },
    }).catch(() => null),
    fetch(`${BASE.replace("/data", "")}/localization/en.json`, {
      headers: HEADERS,
      next: { revalidate: 3600 },
    }).catch(() => null),
    fetch(`${BASE}/summary.json.gz`, {
      headers: { ...HEADERS, "Accept-Encoding": "identity" },
      next: { revalidate: 60 },
    }).catch(() => null),
  ]);

  // Parse localization
  let awardTitle = key.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
  let awardDesc: string | null = null;
  if (locRes?.ok) {
    const loc = await locRes.json();
    if (loc[`award.${key}.title`]) awardTitle = loc[`award.${key}.title`];
    if (loc[`award.${key}.desc`]) awardDesc = loc[`award.${key}.desc`];
  }

  // Parse award units from summary
  let awardUnits: Record<string, string> | null = null;
  if (summaryRes?.ok) {
    try {
      const buffer = Buffer.from(await summaryRes.arrayBuffer());
      let text: string;
      if (buffer[0] === 0x1f && buffer[1] === 0x8b) {
        text = gunzipSync(buffer).toString();
      } else {
        text = buffer.toString();
      }
      const summary = JSON.parse(text);
      if (summary.awards) {
        awardUnits = {};
        for (const [k, v] of Object.entries(summary.awards) as [string, any][]) {
          if (v.unit) awardUnits[k] = v.unit;
        }
      }
    } catch {}
  }

  // Parse rankings and fetch player names
  let entries: { rank: number; uuid: string; name: string; value: number }[] = [];
  if (rankingsRes?.ok) {
    const rankings: { uuid: string; value: number }[] = await rankingsRes.json();

    const prefixes = [...new Set(rankings.map((r) => getCachePrefix(r.uuid)))];
    const cacheResults = await Promise.all(
      prefixes.map((prefix) =>
        fetch(`${BASE}/playercache/${prefix}.json`, {
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

    entries = rankings.map((r, i) => ({
      rank: i + 1,
      uuid: r.uuid,
      name: nameMap.get(r.uuid) ?? "Unknown",
      value: r.value,
    }));
  }

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-3xl">
        {/* Header */}
        <div className="text-center mb-10 animate-in">
          <div className="flex items-center justify-center gap-3 mb-2">
            <Image
              src={`https://map.crabcraft.net/stats/img/award-icons/${key}.png`}
              alt=""
              width={48}
              height={48}
              unoptimized
              className="pixelated"
            />
            <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
              {awardTitle}
            </h1>
          </div>
          {awardDesc && (
            <p className="text-gray-600 dark:text-gray-400">{awardDesc}</p>
          )}
          <p className="mt-1 text-sm text-gray-400 dark:text-gray-500">
            {entries.length} players ranked
          </p>
        </div>

        {/* Leaderboard table */}
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
                  alt={entry.name}
                  width={32}
                  height={32}
                  className="rounded shrink-0"
                />
                <span className="flex-1 font-bold text-sm text-gray-800 dark:text-gray-200 truncate">
                  {entry.name}
                </span>
                <span className="text-sm font-bold text-gray-600 dark:text-gray-400 shrink-0">
                  {formatValue(entry.value, key, awardUnits)}
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

        {/* Back link */}
        <div className="mt-8 text-center animate-in" style={{ animationDelay: "0.2s" }}>
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
