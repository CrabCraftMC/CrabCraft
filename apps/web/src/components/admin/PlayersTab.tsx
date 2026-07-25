"use client";

import type { AdminUser } from "@crabcraft/shared/types";
import { setRoleAction } from "@/app/admin/actions";
import PixelIcon from "@/components/PixelIcon";
import { Button } from "@/components/ui/button";
import Link from "next/link";
import { formatUnixDate } from "@/lib/formatUnixDate";

export default function PlayersTab({
  players,
  search,
  page,
  totalPages,
  totalCount,
  isAdmin,
}: {
  players: AdminUser[];
  search?: string;
  page: number;
  totalPages: number;
  totalCount: number;
  isAdmin: boolean;
}) {
  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="text-xl font-bold">Players ({totalCount})</h2>
        <form action="/admin" method="GET" className="flex gap-2">
          <input type="hidden" name="tab" value="players" />
          <input
            name="q"
            placeholder="Search players..."
            defaultValue={search}
            className="min-w-0 flex-1 rounded-lg border border-[var(--line)] bg-[var(--paper)] px-3 py-1.5 text-sm"
          />
          <Button type="submit" size="sm" variant="outline">
            Search
          </Button>
        </form>
      </div>

      <div className="overflow-x-auto rounded-xl bg-[var(--paper-2)]">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-[var(--line)] text-left">
              <th className="px-3 py-3 font-medium opacity-60 sm:px-4">Minecraft</th>
              <th className="hidden px-4 py-3 font-medium opacity-60 sm:table-cell">Discord</th>
              <th className="hidden px-4 py-3 font-medium opacity-60 md:table-cell">Joined</th>
              <th className="hidden px-4 py-3 font-medium opacity-60 md:table-cell">Last Login</th>
              <th className="px-3 py-3 font-medium opacity-60 sm:px-4">Role</th>
              <th className="px-3 py-3 font-medium opacity-60 sm:px-4"></th>
            </tr>
          </thead>
          <tbody>
            {players.map((player) => (
              <tr
                key={player.discord_id}
                className="border-b border-[var(--line)]/30 transition-colors hover:bg-[var(--paper)]/50"
              >
                <td className="px-3 py-2.5 sm:px-4">
                  <div className="flex items-center gap-2">
                    {player.minecraft_uuid && (
                      <PixelIcon
                        src={`https://mc-heads.net/avatar/${player.minecraft_uuid}/24`}
                        alt=""
                        size={24}
                        className="hidden sm:inline-flex"
                        imgClassName="rounded"
                      />
                    )}
                    <span className="font-medium">
                      {player.minecraft_username ?? "—"}
                    </span>
                  </div>
                </td>
                <td className="hidden px-4 py-2.5 opacity-60 sm:table-cell">{player.discord_username}</td>
                <td className="hidden px-4 py-2.5 opacity-60 md:table-cell">{player.joined_season ?? "—"}</td>
                <td className="hidden px-4 py-2.5 opacity-60 md:table-cell">
                  {player.last_login_at ? formatUnixDate(player.last_login_at) : "Never"}
                </td>
                <td className="px-3 py-2.5 sm:px-4">
                  {isAdmin ? (
                    <form action={setRoleAction}>
                      <input type="hidden" name="discordId" value={player.discord_id} />
                      <select
                        name="role"
                        defaultValue={player.role}
                        onChange={(e) => e.target.form?.requestSubmit()}
                        className={`rounded-full px-2.5 py-0.5 text-xs font-medium transition-colors cursor-pointer bg-[var(--paper)] border border-[var(--line)] ${
                          player.role === "admin" ? "text-red-400" :
                          player.role === "moderator" ? "text-orange-400" :
                          player.role === "verified" ? "text-green-400" :
                          "text-zinc-400"
                        }`}
                      >
                        <option value="unverified">Unverified</option>
                        <option value="verified">Verified</option>
                        <option value="moderator">Moderator</option>
                        <option value="admin">Admin</option>
                      </select>
                    </form>
                  ) : (
                    <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium capitalize ${
                      player.role === "admin" ? "bg-red-500/20 text-red-400" :
                      player.role === "moderator" ? "bg-orange-500/20 text-orange-400" :
                      player.role === "verified" ? "bg-green-500/20 text-green-400" :
                      "bg-zinc-500/20 text-zinc-400"
                    }`}>
                      {player.role}
                    </span>
                  )}
                </td>
                <td className="px-3 py-2.5 sm:px-4">
                  {player.minecraft_uuid && (
                    <Link
                      href={`/stats/${player.minecraft_uuid}`}
                      className="text-xs text-orange-500 hover:underline"
                    >
                      Stats
                    </Link>
                  )}
                </td>
              </tr>
            ))}
            {players.length === 0 && (
              <tr>
                <td colSpan={6} className="py-8 text-center opacity-40">
                  {search ? "No players match your search" : "No players yet"}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <p className="text-sm opacity-50">
            Page {page} of {totalPages}
          </p>
          <div className="flex gap-2">
            {page > 1 && (
              <Link
                href={`/admin?tab=players${search ? `&q=${encodeURIComponent(search)}` : ""}&page=${page - 1}`}
                className="rounded-lg bg-[var(--paper-2)] px-3 py-1.5 text-sm hover:bg-[var(--paper)]"
              >
                Previous
              </Link>
            )}
            {page < totalPages && (
              <Link
                href={`/admin?tab=players${search ? `&q=${encodeURIComponent(search)}` : ""}&page=${page + 1}`}
                className="rounded-lg bg-[var(--paper-2)] px-3 py-1.5 text-sm hover:bg-[var(--paper)]"
              >
                Next
              </Link>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
