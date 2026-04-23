"use client";

import Image from "next/image";
import Squircle from "@/components/Squircle";

interface PlayerAlt {
  id: number;
  discord_id: string;
  minecraft_uuid: string;
  minecraft_username: string;
  created_at: number;
}

export default function AltsTab({
  alts,
}: {
  alts: PlayerAlt[];
  maxAlts: number;
  minecraftUuid: string | null;
}) {
  return (
    <div className="space-y-6 relative">
      {/* Disabled overlay */}
      <div className="absolute inset-0 z-10 flex items-center justify-center pointer-events-none">
        <div className="bg-rose-400/10 border border-rose-300/20 backdrop-blur-sm rounded-2xl px-8 py-4 rotate-[-6deg]">
          <p className="text-rose-300 text-2xl font-bold tracking-wide">Disabled</p>
          <p className="text-rose-300/70 text-sm text-center">returning soon</p>
        </div>
      </div>

      <div className="opacity-30 pointer-events-none select-none">
        <div className="flex items-center justify-between">
          <h2 className="text-xl font-bold">Alt Accounts</h2>
        </div>

        {/* Alt list */}
        <div className="space-y-3 mt-6">
          {alts.map((alt) => (
            <Squircle
              key={alt.id}
              cornerRadius={20}
              className="flex items-center gap-4 bg-[var(--paper-2)] p-4"
            >
              <Image
                src={`https://mc-heads.net/avatar/${alt.minecraft_uuid}/40.png`}
                alt={alt.minecraft_username}
                width={40}
                height={40}
                className="rounded"
              />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold truncate">{alt.minecraft_username}</p>
                <p className="text-xs opacity-50">
                  Linked {new Date(alt.created_at * 1000).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })}
                </p>
              </div>
            </Squircle>
          ))}
          {alts.length === 0 && (
            <p className="py-8 text-center opacity-40">No alt accounts linked</p>
          )}
        </div>
      </div>
    </div>
  );
}
