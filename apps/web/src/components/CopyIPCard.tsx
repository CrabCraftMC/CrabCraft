"use client";

import { useState, useEffect, useRef } from "react";
import { useWebHaptics } from "web-haptics/react";
import Image from "next/image";
import Link from "next/link";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import { ColoredNickname } from "@/lib/parseMinecraftColors";
import { playerDisplayName } from "@/lib/playerName";
import { AnalyticsEvent } from "@crabcraft/shared/analytics";
import { captureWebEvent } from "@/lib/analytics";

interface OnlinePlayer {
  name: string;
  uuid: string;
  nickname?: string;
  nickname_raw?: string;
}

const HEAD_SIZE = 28;
const PLAYER_GAP = 6;
const PLAYER_ROWS = 3;
const MORE_LABEL_WIDTH = 72;
const INITIAL_VISIBLE = 21;

function getVisiblePlayerCount(containerWidth: number, playerCount: number) {
  if (containerWidth <= 0 || playerCount <= 0) return 0;
  const playersPerRow = Math.max(
    1,
    Math.floor((containerWidth + PLAYER_GAP) / (HEAD_SIZE + PLAYER_GAP))
  );
  const capacity = playersPerRow * PLAYER_ROWS;
  if (playerCount <= capacity) return playerCount;

  const overflowSlots = Math.ceil(
    (MORE_LABEL_WIDTH + PLAYER_GAP) / (HEAD_SIZE + PLAYER_GAP)
  );
  return Math.max(0, capacity - overflowSlots);
}

export default function CopyIPCard({
  onlinePlayers,
  onlinePlayerList,
}: {
  onlinePlayers: number;
  onlinePlayerList: OnlinePlayer[];
}) {
  const [players, setPlayers] = useState(onlinePlayerList);
  const [count, setCount] = useState(onlinePlayers);
  const [copied, setCopied] = useState(false);
  const [visibleCount, setVisibleCount] = useState(INITIAL_VISIBLE);
  const playerListRef = useRef<HTMLDivElement>(null);
  const [tooltip, setTooltip] = useState<{
    name: string;
    nickname?: string;
    nickname_raw?: string;
    x: number;
    y: number;
  } | null>(null);

  useEffect(() => {
    const poll = async () => {
      try {
        const res = await fetch("https://api.crabcraft.net/players");
        if (!res.ok) return;
        const data = await res.json();
        setCount(data.count ?? 0);
        setPlayers(
          (data.players || []).map((p: any) => ({
            name: p.username,
            uuid: p.uuid,
            nickname: p.nickname,
            nickname_raw: p.nickname_raw,
          }))
        );
      } catch {}
    };

    const id = setInterval(poll, 30_000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    const playerList = playerListRef.current;
    if (!playerList) return;

    const updateVisibleCount = () => {
      setVisibleCount(getVisiblePlayerCount(playerList.clientWidth, players.length));
    };

    updateVisibleCount();
    const observer = new ResizeObserver(updateVisibleCount);
    observer.observe(playerList);
    return () => observer.disconnect();
  }, [players.length]);

  const { trigger } = useWebHaptics();

  const handleCopy = () => {
    navigator.clipboard.writeText("crabcraft.net");
    captureWebEvent(AnalyticsEvent.SERVER_ADDRESS_COPIED, {
      location: "home_card",
    });
    trigger();
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  const visible = players.slice(0, visibleCount);
  const overflow = players.length - visible.length;

  return (
    <>
    <Squircle
      cornerRadius={32}
      className="lg:col-span-2 card-hover animate-in p-6 lg:p-8 relative overflow-hidden bg-gradient-to-br from-[#F97316] to-[#FB923C] cursor-pointer"
      style={{ animationDelay: "0.45s" }}
      onClick={handleCopy}
    >
      <span className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-[80px] sm:text-[100px] lg:text-[150px] font-bold text-white/10 z-0 select-none pointer-events-none whitespace-nowrap hidden sm:block">
        PLAY
      </span>
      <div className="absolute bottom-0 right-4 pointer-events-none z-0 hidden sm:block">
        <Image
          src="/play.webp"
          alt=""
          width={190}
          height={380}
          sizes="190px"
          style={{ width: "auto", height: "auto" }}
          className="h-[300px] lg:h-[380px] opacity-80"
        />
      </div>
      <div className="relative z-10 flex flex-col justify-between h-full">
        <div>
          <h2 className="text-2xl lg:text-3xl font-bold text-white">
            Join the Server
          </h2>
          <p className="text-white/70 text-sm mt-2">
            <span className="inline-block w-2 h-2 bg-green-400 rounded-full animate-pulse mr-1.5 align-middle" />
            <span className="text-white font-bold">{count}</span>{" "}
            players online
          </p>
          {players.length > 0 && (
            <div
              ref={playerListRef}
              className="mt-3 flex max-w-full flex-wrap gap-1.5 sm:w-[calc(100%-9rem)] lg:w-[calc(100%-11rem)]"
            >
              {visible.map((p) => (
                <Link
                  key={p.uuid}
                  href={`/stats/${p.uuid}`}
                  onClick={(e) => e.stopPropagation()}
                  onMouseEnter={(e) => {
                    const rect = e.currentTarget.getBoundingClientRect();
                    setTooltip({
                      name: p.name,
                      nickname: p.nickname,
                      nickname_raw: p.nickname_raw,
                      x: rect.left + rect.width / 2,
                      y: rect.top - 4,
                    });
                  }}
                  onMouseLeave={() => setTooltip(null)}
                  className="rounded transition-transform hover:scale-110 hover:z-10"
                >
                  <PixelIcon
                    src={`https://mc-heads.net/avatar/${p.uuid}/16.png`}
                    alt={playerDisplayName(p.nickname, p.name)}
                    size={HEAD_SIZE}
                    imgClassName="rounded"
                  />
                </Link>
              ))}
              {overflow > 0 && (
                <span className="flex w-[72px] shrink-0 items-center pl-1 text-xs font-bold text-white/70">
                  +{overflow} more
                </span>
              )}
            </div>
          )}
        </div>
        <div className="mt-4">
          <p className="text-4xl lg:text-6xl font-bold text-white font-mc">
            crabcraft.net
          </p>
          <p className="text-white/70 text-sm mt-2">
            Java Edition — click to copy IP
          </p>
          <p
            className={`text-white/90 text-sm font-bold mt-1 transition-opacity duration-300 ${copied ? "opacity-100" : "opacity-0"}`}
          >
            Copied!
          </p>
        </div>
      </div>
    </Squircle>
    {tooltip && (
      <div
        className="fixed z-[60] pointer-events-none -translate-x-1/2 -translate-y-full animate-[fadeIn_0.1s_ease-out]"
        style={{ left: tooltip.x, top: tooltip.y }}
      >
        <div className="bg-gray-900/90 backdrop-blur-sm text-white text-xs font-bold px-2.5 py-1 rounded-lg shadow-xl whitespace-nowrap">
          {tooltip.nickname_raw ? (
            <ColoredNickname raw={tooltip.nickname_raw} exact />
          ) : (
            playerDisplayName(tooltip.nickname, tooltip.name)
          )}
        </div>
        <div className="flex justify-center">
          <div className="w-2 h-2 bg-gray-900/90 rotate-45 -mt-1" />
        </div>
      </div>
    )}
    </>
  );
}
