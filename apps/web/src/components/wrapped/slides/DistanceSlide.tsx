import type { WrappedData } from "../WrappedContainer";

const DISTANCE_TYPES = [
    { key: "walk_distance_m", label: "Walking", color: "bg-green-400" },
    { key: "sprint_distance_m", label: "Sprinting", color: "bg-yellow-400" },
    { key: "boat_distance_m", label: "Boat", color: "bg-blue-400" },
    { key: "elytra_distance_m", label: "Elytra", color: "bg-purple-400" },
    { key: "horse_distance_m", label: "Horse", color: "bg-amber-400" },
    { key: "swim_distance_m", label: "Swimming", color: "bg-cyan-400" },
] as const;

export default function DistanceSlide({ data }: { data: WrappedData }) {
    const totalKm = (data.stats.total_distance_m / 1000).toFixed(1);

    const sorted = DISTANCE_TYPES
        .map(d => ({ ...d, value: (data.stats as any)[d.key] as number }))
        .filter(d => d.value > 0)
        .sort((a, b) => b.value - a.value);

    const max = sorted[0]?.value || 1;

    return (
        <div className="text-white">
            <p className="text-white/60 text-xs uppercase tracking-widest mb-2">Distance</p>
            <p className="text-5xl font-bold font-mc">{totalKm}</p>
            <p className="text-white/70 text-sm mb-6">kilometres traveled</p>

            <div className="space-y-2.5">
                {sorted.map(d => (
                    <div key={d.key}>
                        <div className="flex justify-between text-xs mb-0.5">
                            <span className="text-white/80">{d.label}</span>
                            <span className="text-white/50">{(d.value / 1000).toFixed(1)}km</span>
                        </div>
                        <div className="h-2 bg-white/15 rounded-full overflow-hidden">
                            <div className={`h-full ${d.color} rounded-full`} style={{ width: `${(d.value / max) * 100}%` }} />
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
}
