import type { WrappedData } from "../WrappedContainer";

function formatId(id: string): string {
    return id.replace("minecraft:", "").replace(/_/g, " ").replace(/\b\w/g, c => c.toUpperCase());
}

export default function BuildingSlide({ data }: { data: WrappedData }) {
    const { total_items_crafted, total_blocks_placed } = data.stats;
    const topCrafted = data.stats.top_item_crafted;
    const topUsed = data.stats.top_item_used;

    return (
        <div className="text-white">
            <p className="text-white/60 text-xs uppercase tracking-widest mb-4">Building</p>

            <div className="space-y-4">
                <div>
                    <p className="text-3xl font-bold font-mc">{total_items_crafted.toLocaleString()}</p>
                    <p className="text-white/60 text-xs">items crafted</p>
                    {topCrafted && <p className="text-white/50 text-xs mt-1">Fav: {formatId(topCrafted.id)}</p>}
                </div>
                <div>
                    <p className="text-3xl font-bold font-mc">{total_blocks_placed.toLocaleString()}</p>
                    <p className="text-white/60 text-xs">blocks placed</p>
                    {topUsed && <p className="text-white/50 text-xs mt-1">Most used: {formatId(topUsed.id)}</p>}
                </div>
            </div>
        </div>
    );
}
