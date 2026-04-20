import type { WrappedData } from "../WrappedContainer";

function formatTime(seconds: number): string {
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    return days > 0 ? `${days}d ${hours}h` : `${hours}h`;
}

export default function SummarySlide({ data }: { data: WrappedData }) {
    const stats = [
        { label: "Play Time", value: formatTime(data.stats.play_time_seconds) },
        { label: "Distance", value: `${(data.stats.total_distance_m / 1000).toFixed(1)} km` },
        { label: "Blocks", value: data.stats.total_blocks_mined.toLocaleString() },
        { label: "Kills", value: data.stats.mob_kills.toLocaleString() },
        { label: "Crafted", value: data.stats.total_items_crafted.toLocaleString() },
        { label: "Deaths", value: data.stats.deaths.toLocaleString() },
    ];

    return (
        <div className="text-white text-center">
            <p className="text-white/60 text-xs uppercase tracking-widest mb-1">That's a wrap</p>
            <h2 className="text-3xl lg:text-4xl font-bold font-mc">{data.playerName}</h2>

            <div className="grid grid-cols-3 gap-3 mt-6">
                {stats.map(s => (
                    <div key={s.label} className="bg-white/10 rounded-xl p-3">
                        <p className="font-bold font-mc text-lg">{s.value}</p>
                        <p className="text-white/50 text-[10px] uppercase">{s.label}</p>
                    </div>
                ))}
            </div>

            <p className="text-white/40 mt-6 text-sm">See you next season</p>
        </div>
    );
}
