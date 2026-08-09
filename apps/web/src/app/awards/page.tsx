import type { Metadata } from "next";
import Link from "next/link";
import AwardsTabs from "@/components/AwardsTabs";
import { categorise } from "@/lib/categories";
import { playerDisplayName } from "@/lib/playerName";

export const metadata: Metadata = {
  title: "Awards",
  description: "Browse all CrabCraft awards and see who holds the #1 spot.",
};

interface ProxyAward {
  id: string;
  title: string;
  description: string;
  unit: string;
  bucket: string;
  icon: string;
  leader: {
    uuid: string;
    username: string;
    nickname: string | null;
    score: number;
  } | null;
}

interface ProxyAwardsResponse {
  awards: ProxyAward[];
}

async function fetchAwards(): Promise<ProxyAwardsResponse | null> {
  try {
    const res = await fetch("https://api.crabcraft.net/awards", {
      next: { revalidate: 30 },
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

export default async function AwardsPage() {
  const data = await fetchAwards();

  const awards = data?.awards ?? [];

  const awardUnits: Record<string, string> = {};
  for (const d of awards) awardUnits[d.id] = d.unit;

  const awardItems = awards.map((d) => ({
    key: d.id,
    title: d.title,
    desc: d.description || null,
    bestName: d.leader
      ? playerDisplayName(d.leader.nickname, d.leader.username)
      : null,
    bestUuid: d.leader?.uuid ?? null,
    bestValue: d.leader?.score ?? 0,
  }));

  const buckets = categorise(awardItems);
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
            {awards.length} awards to compete for
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
