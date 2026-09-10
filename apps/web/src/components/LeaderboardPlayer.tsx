import type { ReactNode } from "react";
import Link from "next/link";
import PixelIcon from "@/components/PixelIcon";
import { playerDisplayName } from "@/lib/playerName";

export interface LeaderboardIdentity {
  hidden?: boolean;
  uuid: string | null;
  username: string | null;
  nickname: string | null;
}

export function leaderboardPlayerName(player: LeaderboardIdentity): string {
  return player.hidden ? "Hidden Player" : playerDisplayName(player.nickname, player.username);
}

export function LeaderboardPlayerLink({ player, className, children }: {
  player: LeaderboardIdentity;
  className: string;
  children: ReactNode;
}) {
  if (player.hidden || !player.uuid) {
    return <div className={`${className.replace(/\bcursor-pointer\b/g, "")} cursor-default`}>{children}</div>;
  }
  return <Link href={`/stats/${player.uuid}`} className={className}>{children}</Link>;
}

export function LeaderboardPlayerAvatar({ player, size, className = "", imgClassName = "rounded" }: {
  player: LeaderboardIdentity;
  size: number;
  className?: string;
  imgClassName?: string;
}) {
  if (player.hidden || !player.uuid) {
    return (
      <span aria-hidden="true" className={`inline-flex shrink-0 items-center justify-center rounded-lg bg-stone-300/70 dark:bg-stone-600/70 text-stone-600 dark:text-stone-200 ring-1 ring-black/5 ${className}`} style={{ width: size, height: size }}>
        <svg viewBox="0 0 16 16" fill="currentColor" className="h-3/5 w-3/5" shapeRendering="crispEdges">
          <path d="M5 2h6v2h2v4h-2v2H9v2H7V8h4V4H5v2H3V4h2zm2 11h2v2H7z" />
        </svg>
      </span>
    );
  }
  return <PixelIcon src={`https://mc-heads.net/avatar/${player.uuid}/100.png`} alt={leaderboardPlayerName(player)} size={size} className={className} imgClassName={imgClassName} />;
}
