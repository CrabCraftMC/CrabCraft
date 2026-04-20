import Link from "next/link";
import Image from "next/image";
import Squircle from "@/components/Squircle";
import PlayerDetailedStats from "@/components/PlayerDetailedStats";

interface PlayerProps {
    nickname: string;
    uuid: string;
    rank: number;
    points: number;
    gold: number;
    silver: number;
    bronze: number;
    found: boolean;
    role: string;
    joinedSeason: string | null;
    detailedStats?: Record<string, { rank?: number; value: number }> | null;
    localization?: Record<string, string> | null;
    awardUnits?: Record<string, string> | null;
    profile?: { discord_username: string | null } | null;
}

export default function PlayerStatsPage(props: PlayerProps) {
    if (!props.found) {
        return (
            <div className="min-h-screen flex flex-col items-center justify-center gap-4 pt-24 pb-16">
                <h1 className="text-3xl font-bold text-gray-800 dark:text-gray-200">Player not found</h1>
                <p className="text-gray-500 dark:text-gray-400">No data for "{props.nickname}"</p>
                <Link href="/leaderboard" className="bg-orange-500 hover:bg-orange-600 text-white font-bold py-2 px-6 rounded-2xl transition-colors">
                    Back to Leaderboard
                </Link>
            </div>
        );
    }

    const { nickname, uuid, rank, points, gold, silver, bronze, role, joinedSeason, detailedStats, localization, awardUnits, profile } = props;

    return (
        <div className="min-h-screen pt-16 lg:pt-24 pb-16">
            <div className="container mx-auto px-4 max-w-4xl">
                {/* Header card */}
                <div className="relative mt-0 lg:mt-12 animate-in">
                    <Squircle cornerRadius={32} className="bg-gradient-to-br from-[#F97316] to-[#FB923C] p-8 lg:p-10 relative overflow-hidden">
                        {rank > 0 && (
                            <span className="hidden sm:block absolute top-1/2 right-6 lg:right-10 -translate-y-1/2 text-[80px] lg:text-[150px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap">
                                #{rank}
                            </span>
                        )}
                        <div className="relative z-10 pl-24 lg:pl-32 flex items-center justify-between">
                            <div>
                                <h1 className="text-3xl lg:text-4xl font-bold text-white flex items-center flex-wrap">
                                    {nickname}
                                    {(role === "moderator" || role === "admin") && (
                                        <span className="ml-2 inline-flex items-center group relative cursor-pointer" tabIndex={0} title="Moderator">
                                            <svg className="w-6 h-6 text-blue-400" viewBox="0 0 24 24" fill="currentColor">
                                                <path d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
                                            </svg>
                                            <span className="absolute -bottom-8 left-1/2 -translate-x-1/2 bg-gray-900 text-white text-xs px-2 py-1 rounded opacity-0 group-hover:opacity-100 group-focus:opacity-100 transition-opacity whitespace-nowrap pointer-events-none">Moderator</span>
                                        </span>
                                    )}
                                </h1>
                                {profile?.discord_username && (
                                    <p className="text-white/50 text-sm mt-0.5 flex items-center gap-1.5">
                                        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="currentColor"><path d="M20.3 4.4A19.6 19.6 0 0 0 15.4 3c-.2.4-.5 1-.7 1.4a18.2 18.2 0 0 0-5.4 0C9.1 4 8.8 3.4 8.6 3A19.5 19.5 0 0 0 3.7 4.4 20.2 20.2 0 0 0 .2 17.2a19.7 19.7 0 0 0 6 3 14.3 14.3 0 0 0 1.2-2 12.8 12.8 0 0 1-2-.9l.5-.4a14 14 0 0 0 12.1 0l.5.4a12.8 12.8 0 0 1-2 .9 14.3 14.3 0 0 0 1.3 2 19.7 19.7 0 0 0 6-3A20.2 20.2 0 0 0 20.3 4.4zM8 14.7c-1.1 0-2-1-2-2.3s.9-2.3 2-2.3 2 1 2 2.3-.9 2.3-2 2.3zm8 0c-1.1 0-2-1-2-2.3s.9-2.3 2-2.3 2 1 2 2.3-.9 2.3-2 2.3z" /></svg>
                                        {profile.discord_username}
                                    </p>
                                )}
                                <p className="text-white/70 text-sm mt-2 flex flex-wrap items-center gap-x-2 gap-y-0.5">
                                    <span>{rank > 0 ? `Rank #${rank}` : "Unranked"}</span>
                                    {joinedSeason && <><span className="text-white/30">·</span><span>Season {joinedSeason}</span></>}
                                </p>
                            </div>
                            <div className="hidden sm:block text-right shrink-0 ml-4">
                                <p className="font-mc text-white text-4xl lg:text-5xl">{points}</p>
                                <p className="text-white/50 text-sm">points</p>
                            </div>
                        </div>
                    </Squircle>
                    <Image
                        src={`https://starlightskins.lunareclipse.studio/render/default/${uuid}/full`}
                        alt={nickname}
                        width={140}
                        height={280}
                        className="absolute bottom-0 -left-2 lg:left-2 h-[220px] lg:h-[280px] w-auto z-20"
                    />
                </div>

                {/* Stats cards */}
                <div className="grid grid-cols-3 gap-4 mt-6">
                    <Squircle cornerRadius={32} className="bg-gradient-to-br from-[#F59E0B] to-[#FBBF24] p-6 text-center animate-in" style={{ animationDelay: "0.1s" }}>
                        <p className="text-white/70 text-xs uppercase tracking-wider">Gold</p>
                        <p className="font-mc text-white text-3xl mt-2">{gold}</p>
                    </Squircle>
                    <Squircle cornerRadius={32} className="bg-gradient-to-br from-[#9CA3AF] to-[#D1D5DB] p-6 text-center animate-in" style={{ animationDelay: "0.15s" }}>
                        <p className="text-white/70 text-xs uppercase tracking-wider">Silver</p>
                        <p className="font-mc text-white text-3xl mt-2">{silver}</p>
                    </Squircle>
                    <Squircle cornerRadius={32} className="bg-gradient-to-br from-[#B45309] to-[#D97706] p-6 text-center animate-in" style={{ animationDelay: "0.2s" }}>
                        <p className="text-white/70 text-xs uppercase tracking-wider">Bronze</p>
                        <p className="font-mc text-white text-3xl mt-2">{bronze}</p>
                    </Squircle>
                </div>

                {/* Detailed stats */}
                {detailedStats && <PlayerDetailedStats stats={detailedStats} localization={localization} awardUnits={awardUnits} />}

                {/* Back link */}
                <div className="mt-8 text-center animate-in" style={{ animationDelay: "0.25s" }}>
                    <Link href="/leaderboard" className="text-sm text-gray-500 dark:text-gray-400 hover:text-orange-500 transition-colors">
                        Back to Leaderboard
                    </Link>
                </div>
            </div>
        </div>
    );
}
