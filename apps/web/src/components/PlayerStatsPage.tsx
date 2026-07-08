import Link from "next/link";
import Squircle from "@/components/Squircle";
import PlayerDetailedStats from "@/components/PlayerDetailedStats";
import PlayerAdvancements from "@/components/PlayerAdvancements";
import { SiTwitch, SiYoutube, SiTiktok } from "react-icons/si";
import { ColoredNickname, parseMinecraftColors } from "@/lib/parseMinecraftColors";

interface PlayerProps {
    nickname: string;
    nicknameRaw?: string | null;
    uuid: string;
    rank: number;
    points: number;
    gold: number;
    silver: number;
    bronze: number;
    currentStreak: number;
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
    advancements?: {
        advancements: Record<string, { completed: boolean; completed_at: number | null }>;
        total: number;
        completed: number;
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

    const { nickname, nicknameRaw, uuid, rank, points, gold, silver, bronze, currentStreak, role, joinedSeason, detailedStats, awardsById, localization, awardUnits, profile, advancements: advancementsData } = props;
    const showStreak = currentStreak >= 3;
    const hasMeta = showStreak || rank > 0 || joinedSeason;
    // Show the real username under a nickname that spells something else
    // (a recolored username needs no repeat).
    const plainNick = nicknameRaw
        ? parseMinecraftColors(nicknameRaw).map((s) => s.text).join("")
        : null;
    const showUsername = Boolean(
        plainNick && plainNick.toLowerCase() !== nickname.toLowerCase(),
    );

    return (
        <div className="min-h-screen pt-16 lg:pt-24 pb-16">
            <div className={`container mx-auto px-4 ${advancementsData ? "max-w-6xl" : "max-w-4xl"}`}>
                {/* Header card */}
                <div className="relative mt-0 lg:mt-12 animate-in">
                    {profile?.channels && profile.channels.length > 0 && (
                        <div className="flex justify-end items-center gap-3 mb-3 pr-2">
                            {profile?.channels?.map((ch) => {
                                const url = ch.platform === "twitch" ? `https://twitch.tv/${ch.channel_id}`
                                    : ch.platform === "youtube" ? `https://youtube.com/${ch.channel_id}`
                                    : `https://tiktok.com/${ch.channel_id}`;
                                const Icon = ch.platform === "twitch" ? SiTwitch
                                    : ch.platform === "youtube" ? SiYoutube
                                    : SiTiktok;
                                const color = ch.platform === "twitch" ? "text-purple-400 hover:text-purple-300"
                                    : ch.platform === "youtube" ? "text-red-400 hover:text-red-300"
                                    : "text-pink-400 hover:text-pink-300";
                                return (
                                    <a key={ch.platform} href={url} target="_blank" rel="noopener noreferrer" className={`${color} transition-all hover:scale-125`} title={ch.display_name || ch.channel_id}>
                                        <Icon className="w-7 h-7" />
                                    </a>
                                );
                            })}
                        </div>
                    )}
                    <Squircle cornerRadius={32} className="bg-gradient-to-br from-[#F97316] to-[#FB923C] p-8 lg:p-10 relative overflow-hidden">
                        {rank > 0 && (
                            <span className="hidden sm:block absolute top-1/2 right-6 lg:right-10 -translate-y-1/2 text-[80px] lg:text-[150px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap">
                                #{rank}
                            </span>
                        )}
                        <div className="relative z-10 pl-24 lg:pl-32 flex items-center justify-between">
                            <div>
                                <h1 className="text-3xl lg:text-4xl font-bold text-white flex items-center flex-wrap">
                                    {nicknameRaw ? <ColoredNickname raw={nicknameRaw} exact /> : nickname}
                                    {(role === "moderator" || role === "admin") && (
                                        <span className="ml-3 inline-flex items-center group relative cursor-pointer" tabIndex={0} title="Moderator">
                                            <img
                                                src="/minecraft/item/mace.png"
                                                alt=""
                                                aria-hidden="true"
                                                className="h-6 w-6 pixelated drop-shadow-[0_0_6px_rgba(96,165,250,0.55)]"
                                            />
                                            <span className="absolute -bottom-8 left-1/2 -translate-x-1/2 bg-gray-900 text-white text-xs px-2 py-1 rounded opacity-0 group-hover:opacity-100 group-focus:opacity-100 transition-opacity whitespace-nowrap pointer-events-none">Moderator</span>
                                        </span>
                                    )}
                                </h1>
                                {showUsername && (
                                    <p className="text-white/50 text-sm mt-0.5">{nickname}</p>
                                )}
                                {profile?.discord_username && (
                                    <p className="text-white/50 text-sm mt-0.5 flex items-center gap-1.5">
                                        <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="currentColor"><path d="M20.3 4.4A19.6 19.6 0 0 0 15.4 3c-.2.4-.5 1-.7 1.4a18.2 18.2 0 0 0-5.4 0C9.1 4 8.8 3.4 8.6 3A19.5 19.5 0 0 0 3.7 4.4 20.2 20.2 0 0 0 .2 17.2a19.7 19.7 0 0 0 6 3 14.3 14.3 0 0 0 1.2-2 12.8 12.8 0 0 1-2-.9l.5-.4a14 14 0 0 0 12.1 0l.5.4a12.8 12.8 0 0 1-2 .9 14.3 14.3 0 0 0 1.3 2 19.7 19.7 0 0 0 6-3A20.2 20.2 0 0 0 20.3 4.4zM8 14.7c-1.1 0-2-1-2-2.3s.9-2.3 2-2.3 2 1 2 2.3-.9 2.3-2 2.3zm8 0c-1.1 0-2-1-2-2.3s.9-2.3 2-2.3 2 1 2 2.3-.9 2.3-2 2.3z" /></svg>
                                        {profile.discord_username}
                                    </p>
                                )}
                                {hasMeta && (
                                <p className="text-white/70 text-sm mt-2 flex flex-wrap items-center gap-x-2 gap-y-0.5">
                                    {showStreak && (
                                        <>
                                            <span
                                                className="group relative inline-flex items-center gap-1 drop-shadow-[0_0_8px_rgba(255,255,255,0.35)]"
                                                tabIndex={0}
                                                aria-label={`${currentStreak} day streak`}
                                            >
                                                <img
                                                    src="/icons/fire-minecraft.gif"
                                                    alt=""
                                                    aria-hidden="true"
                                                    className="h-4 w-4 rounded-full object-cover"
                                                />
                                                <span className="font-bold leading-none text-white">
                                                    {currentStreak}
                                                </span>
                                                <span className="absolute left-1/2 top-full mt-2 -translate-x-1/2 opacity-0 invisible group-hover:opacity-100 group-hover:visible group-focus:opacity-100 group-focus:visible transition-all duration-200 z-50 whitespace-nowrap pointer-events-none">
                                                    <span className="absolute left-1/2 -top-1.5 h-3 w-3 -translate-x-1/2 rotate-45 bg-paper-2 dark:bg-[#2a221b] border-l border-t border-gray-200 dark:border-[#3d3028]" />
                                                    <span className="relative block px-2.5 py-1.5 bg-paper-2 dark:bg-[#2a221b] rounded-lg shadow-lg border border-gray-200 dark:border-[#3d3028] text-xs font-bold text-gray-700 dark:text-gray-200">
                                                        {currentStreak} day streak
                                                    </span>
                                                </span>
                                            </span>
                                            {(rank > 0 || joinedSeason) && <span className="text-white/30">·</span>}
                                        </>
                                    )}
                                    {rank > 0 && <span>Rank #{rank}</span>}
                                    {rank > 0 && joinedSeason && <span className="text-white/30">·</span>}
                                    {joinedSeason && <span>Season {joinedSeason}</span>}
                                </p>
                                )}
                            </div>
                            <div className="hidden sm:block text-right shrink-0 ml-4">
                                <p className="font-mc text-white text-4xl lg:text-5xl">{points}</p>
                                <p className="text-white/50 text-sm">points</p>
                            </div>
                        </div>
                    </Squircle>
                    <img
                        src={`https://mc-api.io/render/full/${encodeURIComponent(nickname)}/java?size=256`}
                        alt={nickname}
                        width={140}
                        height={280}
                        className="absolute bottom-0 left-3 lg:left-6 h-[147px] lg:h-[187px] w-auto z-20"
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

                {/* Two-column layout: stats left, advancements right */}
                {advancementsData ? (
                    <div className="grid grid-cols-1 lg:grid-cols-[13fr_7fr] gap-4 mt-6">
                        <div className="min-w-0">
                            <PlayerDetailedStats stats={detailedStats ?? {}} awardsById={awardsById ?? undefined} localization={localization} awardUnits={awardUnits} />
                        </div>
                        <div className="min-w-0">
                            <PlayerAdvancements
                                advancements={advancementsData.advancements}
                                total={advancementsData.total}
                                completed={advancementsData.completed}
                            />
                        </div>
                    </div>
                ) : (
                    <PlayerDetailedStats stats={detailedStats ?? {}} awardsById={awardsById ?? undefined} localization={localization} awardUnits={awardUnits} />
                )}

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
