import type { WrappedData } from "../WrappedContainer";

function formatTime(seconds: number): { hours: number; minutes: number } {
    return { hours: Math.floor(seconds / 3600), minutes: Math.floor((seconds % 3600) / 60) };
}

export default function PlaytimeSlide({ data }: { data: WrappedData }) {
    const { hours, minutes } = formatTime(data.stats.play_time_seconds);
    const avgHours = Math.round(data.averages.avg_play_time / 3600);
    const ratio = avgHours > 0 ? (hours / avgHours) : 1;
    const percent = Math.min(ratio * 50, 100);
    const rank = data.ranks.play_time_seconds;

    const circumference = 2 * Math.PI * 70;
    const strokeDashoffset = circumference - (percent / 100) * circumference;

    return (
        <div className="text-white text-center">
            <p className="text-white/60 text-xs uppercase tracking-widest mb-6">Play Time</p>

            <div className="relative inline-block">
                <svg width="180" height="180" className="mx-auto">
                    <circle cx="90" cy="90" r="70" fill="none" stroke="rgba(255,255,255,0.15)" strokeWidth="8" />
                    <circle
                        cx="90" cy="90" r="70" fill="none" stroke="white" strokeWidth="8"
                        strokeLinecap="round"
                        strokeDasharray={circumference}
                        strokeDashoffset={strokeDashoffset}
                        transform="rotate(-90 90 90)"
                        className="transition-all duration-1000"
                    />
                </svg>
                <div className="absolute inset-0 flex flex-col items-center justify-center">
                    <span className="text-4xl font-bold font-mc">{hours}</span>
                    <span className="text-white/60 text-sm">hours</span>
                </div>
            </div>

            <div className="mt-6 space-y-2">
                <p className="text-white/70 text-sm">{ratio.toFixed(1)}x the server average</p>
                <p className="text-white font-bold text-lg">#{rank} <span className="text-white/50 text-xs font-normal">of {data.totalPlayers}</span></p>
            </div>
        </div>
    );
}
