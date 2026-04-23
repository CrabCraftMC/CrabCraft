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
    awardsById?: Record<string, { title: string; description: string; icon: string }> | null;
    localization?: Record<string, string> | null;
    awardUnits?: Record<string, string> | null;
    profile?: {
        discord_username: string | null;
        channels?: Array<{ platform: string; channel_id: string; display_name: string | null }>;
    } | null;
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

    const { nickname, uuid, rank, points, gold, silver, bronze, role, joinedSeason, detailedStats, awardsById, localization, awardUnits, profile } = props;

    return (
        <div className="min-h-screen pt-16 lg:pt-24 pb-16">
            <div className="container mx-auto px-4 max-w-4xl">
                {/* Header card */}
                <div className="relative mt-0 lg:mt-12 animate-in">
                    <Squircle cornerRadius={32} className="bg-gradient-to-br from-[#F97316] to-[#FB923C] p-8 lg:p-10 relative overflow-hidden">
                        {profile?.channels && profile.channels.length > 0 && (
                            <div className="absolute top-4 right-4 lg:top-6 lg:right-6 z-20 flex items-center gap-2">
                                {profile.channels.map((ch) => {
                                    const url = ch.platform === "twitch" ? `https://twitch.tv/${ch.channel_id}`
                                        : ch.platform === "youtube" ? `https://youtube.com/${ch.channel_id}`
                                        : `https://tiktok.com/${ch.channel_id}`;
                                    return (
                                        <a key={ch.platform} href={url} target="_blank" rel="noopener noreferrer" className="text-white/60 hover:text-white transition-colors" title={ch.display_name || ch.channel_id}>
                                            {ch.platform === "twitch" && (
                                                <svg className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor"><path d="M11.571 4.714h1.715v5.143H11.57zm4.715 0H18v5.143h-1.714zM6 0L1.714 4.286v15.428h5.143V24l4.286-4.286h3.428L22.286 12V0zm14.571 11.143l-3.428 3.428h-3.429l-3 3v-3H6.857V1.714h13.714z"/></svg>
                                            )}
                                            {ch.platform === "youtube" && (
                                                <svg className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor"><path d="M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12z"/></svg>
                                            )}
                                            {ch.platform === "tiktok" && (
                                                <svg className="w-5 h-5" viewBox="0 0 24 24" fill="currentColor"><path d="M12.525.02c1.31-.02 2.61-.01 3.91-.02.08 1.53.63 3.09 1.75 4.17 1.12 1.11 2.7 1.62 4.24 1.79v4.03c-1.44-.05-2.89-.35-4.2-.97-.57-.26-1.1-.59-1.62-.93-.01 2.92.01 5.84-.02 8.75-.08 1.4-.54 2.79-1.35 3.94-1.31 1.92-3.58 3.17-5.91 3.21-1.43.08-2.86-.31-4.08-1.03-2.02-1.19-3.44-3.37-3.65-5.71-.02-.5-.03-1-.01-1.49.18-1.9 1.12-3.72 2.58-4.96 1.66-1.44 3.98-2.13 6.15-1.72.02 1.48-.04 2.96-.04 4.44-.99-.32-2.15-.23-3.02.37-.63.41-1.11 1.04-1.36 1.75-.21.51-.15 1.07-.14 1.61.24 1.64 1.82 3.02 3.5 2.87 1.12-.01 2.19-.66 2.77-1.61.19-.33.4-.67.41-1.06.1-1.79.06-3.57.07-5.36.01-4.03-.01-8.05.02-12.07z"/></svg>
                                            )}
                                        </a>
                                    );
                                })}
                            </div>
                        )}
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
                {detailedStats && <PlayerDetailedStats stats={detailedStats} awardsById={awardsById ?? undefined} localization={localization} awardUnits={awardUnits} />}

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
