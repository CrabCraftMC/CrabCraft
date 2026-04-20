"use client";

import type { Application, Season } from "@crabcraft/shared/types";
import Link from "next/link";

const STATUS_COLORS: Record<string, string> = {
  accepted: "bg-green-500/20 text-green-400",
  pending: "bg-yellow-500/20 text-yellow-400",
  denied: "bg-red-500/20 text-red-400",
  cancelled: "bg-zinc-500/20 text-zinc-400",
};

function formatDate(unix: number) {
  return new Date(unix * 1000).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

export default function ApplicationsTab({
  applications,
  seasons,
  currentStatus,
  currentSeason,
}: {
  applications: Application[];
  seasons: Season[];
  currentStatus?: string;
  currentSeason?: string;
}) {
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h2 className="text-xl font-bold">Applications ({applications.length})</h2>
        <div className="flex gap-2">
          <FilterLink label="All" tab="applications" active={!currentStatus && !currentSeason} />
          <FilterLink label="Pending" tab="applications" status="pending" active={currentStatus === "pending"} />
          <FilterLink label="Accepted" tab="applications" status="accepted" active={currentStatus === "accepted"} />
          <FilterLink label="Denied" tab="applications" status="denied" active={currentStatus === "denied"} />
          {seasons.length > 0 && (
            <select
              defaultValue={currentSeason ?? ""}
              onChange={(e) => {
                const params = new URLSearchParams();
                params.set("tab", "applications");
                if (currentStatus) params.set("status", currentStatus);
                if (e.target.value) params.set("season", e.target.value);
                window.location.href = `/admin?${params.toString()}`;
              }}
              className="rounded-lg border border-[var(--line)] bg-[var(--paper)] px-2 py-1 text-xs"
            >
              <option value="">All seasons</option>
              {seasons.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          )}
        </div>
      </div>

      <div className="overflow-x-auto rounded-xl bg-[var(--paper-2)]">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-[var(--line)] text-left">
              <th className="px-3 py-3 font-medium opacity-60 sm:px-4">Player</th>
              <th className="hidden px-4 py-3 font-medium opacity-60 sm:table-cell">Discord</th>
              <th className="px-3 py-3 font-medium opacity-60 sm:px-4">Status</th>
              <th className="hidden px-4 py-3 font-medium opacity-60 md:table-cell">Season</th>
              <th className="hidden px-4 py-3 font-medium opacity-60 md:table-cell">Reason</th>
              <th className="hidden px-4 py-3 font-medium opacity-60 sm:table-cell">Applied</th>
            </tr>
          </thead>
          <tbody>
            {applications.map((app, i) => (
              <tr
                key={i}
                className="border-b border-[var(--line)]/30 transition-colors hover:bg-[var(--paper)]/50"
              >
                <td className="px-3 py-2.5 font-medium sm:px-4">{app.minecraft_username}</td>
                <td className="hidden px-4 py-2.5 opacity-60 sm:table-cell">{app.discord_username}</td>
                <td className="px-3 py-2.5 sm:px-4">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium capitalize ${STATUS_COLORS[app.status] ?? ""}`}
                  >
                    {app.status}
                  </span>
                </td>
                <td className="hidden px-4 py-2.5 opacity-60 md:table-cell">{app.season || "—"}</td>
                <td className="hidden max-w-xs truncate px-4 py-2.5 opacity-60 md:table-cell" title={app.join_reason}>
                  {app.join_reason || "—"}
                </td>
                <td className="hidden px-4 py-2.5 opacity-60 sm:table-cell">{formatDate(app.applied_at)}</td>
              </tr>
            ))}
            {applications.length === 0 && (
              <tr>
                <td colSpan={6} className="py-8 text-center opacity-40">
                  No applications match the current filters
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function FilterLink({
  label,
  tab,
  status,
  active,
}: {
  label: string;
  tab: string;
  status?: string;
  active: boolean;
}) {
  const params = new URLSearchParams();
  params.set("tab", tab);
  if (status) params.set("status", status);

  return (
    <Link
      href={`/admin?${params.toString()}`}
      className={`rounded-lg px-3 py-1 text-xs font-medium transition-colors ${
        active
          ? "bg-orange-500 text-white"
          : "bg-[var(--paper)] text-[var(--foreground)]/60 hover:text-[var(--foreground)]"
      }`}
    >
      {label}
    </Link>
  );
}
