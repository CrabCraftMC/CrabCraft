import type { WrappedData } from "../WrappedContainer";

const CATEGORIES = [
    { key: "play_time_seconds", label: "Play Time" },
    { key: "total_blocks_mined", label: "Mining" },
    { key: "mob_kills", label: "Kills" },
    { key: "total_distance_m", label: "Distance" },
    { key: "total_items_crafted", label: "Crafting" },
    { key: "deaths", label: "Deaths" },
] as const;

export default function RankingsSlide({ data }: { data: WrappedData }) {
    return (
        <div className="text-white">
            <p className="text-white/60 text-xs uppercase tracking-widest mb-4">Your Rankings</p>

            <div className="space-y-3">
                {CATEGORIES.map(cat => {
                    const rank = data.ranks[cat.key] || 0;
                    const total = data.totalPlayers;
                    const percent = total > 0 ? ((total - rank) / total) * 100 : 0;
                    const isTop3 = rank > 0 && rank <= 3;

                    return (
                        <div key={cat.key}>
                            <div className="flex justify-between text-xs mb-1">
                                <span className="text-white/70">{cat.label}</span>
                                <span className={`font-bold ${isTop3 ? 'text-yellow-300' : 'text-white'}`}>#{rank}</span>
                            </div>
                            <div className="h-2 bg-white/15 rounded-full overflow-hidden">
                                <div
                                    className={`h-full rounded-full ${isTop3 ? 'bg-yellow-300' : 'bg-white/60'}`}
                                    style={{ width: `${percent}%` }}
                                />
                            </div>
                        </div>
                    );
                })}
            </div>

            <p className="text-white/40 text-[10px] mt-3">of {data.totalPlayers} players</p>
        </div>
    );
}
