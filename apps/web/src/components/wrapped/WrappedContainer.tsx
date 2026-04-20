"use client";

import Link from "next/link";
import type { PlayerSeasonStats } from "../../lib/types";
import IntroSlide from "./slides/IntroSlide";
import PlaytimeSlide from "./slides/PlaytimeSlide";
import DistanceSlide from "./slides/DistanceSlide";
import MiningSlide from "./slides/MiningSlide";
import CombatSlide from "./slides/CombatSlide";
import BuildingSlide from "./slides/BuildingSlide";
import FunFactsSlide from "./slides/FunFactsSlide";
import RankingsSlide from "./slides/RankingsSlide";
import SummarySlide from "./slides/SummarySlide";

export interface WrappedData {
    stats: PlayerSeasonStats;
    averages: Record<string, number>;
    ranks: Record<string, number>;
    playerName: string;
    playerUuid: string;
    season: string;
    totalPlayers: number;
}

export default function WrappedContainer({ data }: { data: WrappedData }) {
    return (
        <div className="pt-24 pb-16">
            <div className="container mx-auto px-4 max-w-5xl">
                <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">

                    {/* Intro — full width, no card */}
                    <div className="col-span-1 lg:col-span-3 p-6 lg:p-8">
                        <IntroSlide data={data} />
                    </div>

                    {/* Playtime — 1 col */}
                    <div className="col-span-1 rounded-3xl card-hover relative overflow-hidden p-6 lg:p-8" style={{ background: "linear-gradient(135deg, #F59E0B, #FBBF24)" }}>
                        <PlaytimeSlide data={data} />
                    </div>

                    {/* Distance — 2 cols */}
                    <div className="col-span-1 lg:col-span-2 rounded-3xl card-hover relative overflow-hidden p-6 lg:p-8" style={{ background: "linear-gradient(135deg, #06B6D4, #22D3EE)" }}>
                        <DistanceSlide data={data} />
                    </div>

                    {/* Mining — 1 col */}
                    <div className="col-span-1 rounded-3xl card-hover relative overflow-hidden p-6 lg:p-8" style={{ background: "linear-gradient(135deg, #16A34A, #4ADE80)" }}>
                        <MiningSlide data={data} />
                    </div>

                    {/* Combat — 1 col */}
                    <div className="col-span-1 rounded-3xl card-hover relative overflow-hidden p-6 lg:p-8" style={{ background: "linear-gradient(135deg, #E11D48, #FB7185)" }}>
                        <CombatSlide data={data} />
                    </div>

                    {/* Building — 1 col */}
                    <div className="col-span-1 rounded-3xl card-hover relative overflow-hidden p-6 lg:p-8" style={{ background: "linear-gradient(135deg, #2563EB, #60A5FA)" }}>
                        <BuildingSlide data={data} />
                    </div>

                    {/* Fun Facts — 1 col */}
                    <div className="col-span-1 rounded-3xl card-hover relative overflow-hidden p-6 lg:p-8" style={{ background: "linear-gradient(135deg, #8B5CF6, #A78BFA)" }}>
                        <FunFactsSlide data={data} />
                    </div>

                    {/* Rankings — 2 cols */}
                    <div className="col-span-1 lg:col-span-2 rounded-3xl card-hover relative overflow-hidden p-6 lg:p-8" style={{ background: "linear-gradient(135deg, #0D9488, #2DD4BF)" }}>
                        <RankingsSlide data={data} />
                    </div>

                    {/* Summary — full width */}
                    <div className="col-span-1 lg:col-span-3 rounded-3xl card-hover relative overflow-hidden p-6 lg:p-8" style={{ background: "linear-gradient(135deg, #F97316, #FB923C)" }}>
                        <SummarySlide data={data} />
                    </div>

                </div>

                <div className="text-left pt-8">
                    <Link href="/wrapped" className="text-sm text-gray-400 dark:text-gray-500 hover:text-orange-500 transition-colors">
                        &larr; Back to seasons
                    </Link>
                </div>
            </div>
        </div>
    );
}
