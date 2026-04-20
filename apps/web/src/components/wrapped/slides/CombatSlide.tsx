import type { WrappedData } from "../WrappedContainer";

function formatId(id: string): string {
    return id.replace("minecraft:", "").replace(/_/g, " ").replace(/\b\w/g, c => c.toUpperCase());
}

export default function CombatSlide({ data }: { data: WrappedData }) {
    const { mob_kills, deaths } = data.stats;
    const kd = deaths > 0 ? (mob_kills / deaths).toFixed(2) : "∞";
    const topMob = data.stats.top_mob_killed;
    const topDeath = data.stats.top_death_cause;

    return (
        <div className="text-white">
            <p className="text-white/60 text-xs uppercase tracking-widest mb-4">Combat</p>

            <div className="flex gap-4 items-end mb-4">
                <div className="flex-1">
                    <p className="text-3xl font-bold font-mc">{mob_kills.toLocaleString()}</p>
                    <p className="text-white/60 text-xs">kills</p>
                </div>
                <p className="text-xl text-white/30 font-bold pb-1">vs</p>
                <div className="flex-1 text-right">
                    <p className="text-3xl font-bold font-mc">{deaths.toLocaleString()}</p>
                    <p className="text-white/60 text-xs">deaths</p>
                </div>
            </div>

            <p className="text-center text-white/50 text-sm">K/D: <span className="text-white font-bold text-lg">{kd}</span></p>

            <div className="grid grid-cols-2 gap-2 mt-4">
                {topMob && (
                    <div className="bg-white/10 rounded-xl p-2.5">
                        <p className="text-white/50 text-[10px] uppercase">Most killed</p>
                        <p className="font-bold text-xs">{formatId(topMob.id)}</p>
                    </div>
                )}
                {topDeath && (
                    <div className="bg-white/10 rounded-xl p-2.5">
                        <p className="text-white/50 text-[10px] uppercase">Died to</p>
                        <p className="font-bold text-xs">{formatId(topDeath.id)}</p>
                    </div>
                )}
            </div>
        </div>
    );
}
