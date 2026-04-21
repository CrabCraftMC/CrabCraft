import type { Metadata } from "next";
import Link from "next/link";
import AwardsTabs from "@/components/AwardsTabs";
import ServerSelect from "@/components/ServerSelect";
import { AWARDS, AWARD_IDS } from "@crabcraft/shared/awards";
import {
  getAwardsSummary,
  getAwardServers,
  getCurrentSeason,
  AWARD_AGGREGATE_SERVER_ID,
} from "@/lib/queries";
import { categorise } from "@/lib/categories";

interface SearchParams {
  server?: string;
}

interface Props {
  searchParams: Promise<SearchParams>;
}

export const metadata: Metadata = {
  title: "Awards",
  description: "Browse all CrabCraft awards and see who holds the #1 spot.",
};

export default async function AwardsPage({ searchParams }: Props) {
  const { server } = await searchParams;
  const currentSeason = await getCurrentSeason();
  const seasonId = currentSeason?.id;

  const serverId = server && server.length > 0 ? server : AWARD_AGGREGATE_SERVER_ID;

  const [summary, servers] = seasonId
    ? await Promise.all([
        getAwardsSummary(seasonId, serverId),
        getAwardServers(seasonId),
      ])
    : [[], []];

  const summaryByAward = new Map(summary.map((s) => [s.award_id, s]));
  const awardUnits: Record<string, string> = {};
  for (const id of AWARD_IDS) awardUnits[id] = AWARDS[id].unit;

  const awardItems = AWARD_IDS.map((id) => {
    const meta = AWARDS[id];
    const best = summaryByAward.get(id);
    return {
      key: id,
      title: meta.title,
      desc: meta.desc || null,
      bestName: best?.best_username ?? null,
      bestUuid: best?.best_uuid ?? null,
      bestValue: best?.best_score ?? 0,
    };
  });

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
            {AWARD_IDS.length} awards to compete for
          </p>
        </div>

        {servers.length > 0 && (
          <div className="flex justify-center mb-6 animate-in">
            <ServerSelect
              servers={servers}
              current={serverId}
              basePath="/awards"
            />
          </div>
        )}

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
