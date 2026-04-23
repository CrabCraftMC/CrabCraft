"use client";

import { useState, useTransition } from "react";
import Image from "next/image";
import { Button } from "@/components/ui/button";
import { addAltAction, removeAltAction } from "@/app/settings/actions";
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
  maxAlts,
  minecraftUuid,
}: {
  alts: PlayerAlt[];
  maxAlts: number;
  minecraftUuid: string | null;
}) {
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();
  const [confirmingUuid, setConfirmingUuid] = useState<string | null>(null);

  const handleAdd = (formData: FormData) => {
    setError(null);
    startTransition(async () => {
      const result = await addAltAction(formData);
      if (!result.success) setError(result.error);
    });
  };

  const handleRemove = (uuid: string) => {
    setError(null);
    const formData = new FormData();
    formData.set("uuid", uuid);
    startTransition(async () => {
      const result = await removeAltAction(formData);
      if (!result.success) setError(result.error);
      setConfirmingUuid(null);
    });
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold">Alt Accounts</h2>
        <span className="text-sm opacity-60 relative group cursor-default">
          {alts.length}/{maxAlts} slots used
          <span className="absolute bottom-full right-0 mb-2 hidden group-hover:block bg-gray-900 text-white text-xs px-3 py-1.5 rounded-lg whitespace-nowrap pointer-events-none">
            Need more slots? Open a ticket in Discord
          </span>
        </span>
      </div>

      {error && (
        <div className="rounded-lg bg-red-500/10 border border-red-500/20 px-4 py-3 text-sm text-red-500">
          {error}
        </div>
      )}

      {/* Alt list */}
      <div className="space-y-3">
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
            {confirmingUuid === alt.minecraft_uuid ? (
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={isPending}
                  onClick={() => handleRemove(alt.minecraft_uuid)}
                >
                  Confirm
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={isPending}
                  onClick={() => setConfirmingUuid(null)}
                >
                  Cancel
                </Button>
              </div>
            ) : (
              <Button
                size="sm"
                variant="outline"
                onClick={() => setConfirmingUuid(alt.minecraft_uuid)}
              >
                Remove
              </Button>
            )}
          </Squircle>
        ))}
        {alts.length === 0 && (
          <p className="py-8 text-center opacity-40">No alt accounts linked</p>
        )}
      </div>

      {/* Add alt form */}
      {alts.length < maxAlts && (
        <Squircle cornerRadius={20} className="bg-[var(--paper-2)] p-5">
          <form action={handleAdd} className="space-y-4">
            <h3 className="font-semibold">Add Alt Account</h3>
            <div className="flex gap-3">
              <input
                name="username"
                placeholder="Minecraft username"
                required
                className="flex-1 rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-2 text-sm"
              />
              <Button type="submit" size="sm" disabled={isPending}>
                {isPending ? "Adding..." : "Add"}
              </Button>
            </div>
            {!minecraftUuid && (
              <p className="text-xs opacity-50">
                You need a linked Minecraft account before adding alts.
              </p>
            )}
          </form>
        </Squircle>
      )}
    </div>
  );
}
