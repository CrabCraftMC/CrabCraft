import type { WrappedData } from "../WrappedContainer";

function formatId(id: string): string {
    return id.replace("minecraft:", "").replace(/_/g, " ").replace(/\b\w/g, c => c.toUpperCase());
}

export default function MiningSlide({ data }: { data: WrappedData }) {
    const blocks = data.stats.total_blocks_mined;
    const topBlock = data.stats.top_block_mined;
    const rank = data.ranks.total_blocks_mined;

    return (
        <div className="text-white">
            <p className="text-white/60 text-xs uppercase tracking-widest mb-2">Mining</p>
            <p className="text-4xl font-bold font-mc">{blocks.toLocaleString()}</p>
            <p className="text-white/70 text-sm">blocks mined</p>

            {topBlock && (
                <div className="mt-4 bg-white/10 rounded-xl p-3">
                    <p className="text-white/50 text-[10px] uppercase">Favourite</p>
                    <p className="font-bold text-sm">{formatId(topBlock.id)}</p>
                    <p className="text-white/50 text-xs">{topBlock.count.toLocaleString()}</p>
                </div>
            )}

            <p className="text-white/70 text-xs mt-4">#{rank} of {data.totalPlayers}</p>
        </div>
    );
}
