import type { Application, Season } from "@crabcraft/shared/types";
import { formatUnixDate as formatDate } from "@/lib/formatUnixDate";

interface OverviewStats {
  playerCount: number;
  applicationsByStatus: Record<string, number>;
  currentSeason: Season | null;
  recentApplications: Application[];
}

const STATUS_COLORS: Record<string, string> = {
  accepted: "bg-green-500/20 text-green-400",
  pending: "bg-yellow-500/20 text-yellow-400",
  denied: "bg-red-500/20 text-red-400",
  cancelled: "bg-zinc-500/20 text-zinc-400",
};

export default function OverviewTab({ stats }: { stats: OverviewStats }) {
  const totalApps = Object.values(stats.applicationsByStatus).reduce((a, b) => a + b, 0);

  return (
    <div className="space-y-4 sm:space-y-6">
      <div className="grid grid-cols-2 gap-3 sm:gap-4 sm:grid-cols-4">
        <StatCard label="Players" value={stats.playerCount} />
        <StatCard label="Applications" value={totalApps} />
        <StatCard
          label="Current Season"
          value={stats.currentSeason?.name ?? "None"}
        />
        <StatCard
          label="Pending"
          value={stats.applicationsByStatus["pending"] ?? 0}
          highlight={true}
        />
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="rounded-xl bg-[var(--paper-2)] p-5">
          <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider opacity-60">
            Applications by Status
          </h3>
          <div className="space-y-2">
            {Object.entries(stats.applicationsByStatus).map(([status, count]) => (
              <div key={status} className="flex items-center justify-between">
                <span
                  className={`rounded-full px-2.5 py-0.5 text-xs font-medium capitalize ${STATUS_COLORS[status] ?? "bg-zinc-500/20 text-zinc-400"}`}
                >
                  {status}
                </span>
                <span className="text-sm font-medium">{count}</span>
              </div>
            ))}
          </div>
        </div>

        {stats.currentSeason && (
          <div className="rounded-xl bg-[var(--paper-2)] p-5">
            <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider opacity-60">
              Current Season
            </h3>
            <p className="text-lg font-bold">{stats.currentSeason.name}</p>
            {stats.currentSeason.start_date && (
              <p className="mt-1 text-sm opacity-60">
                Started: {stats.currentSeason.start_date}
                {stats.currentSeason.end_date && ` — Ends: ${stats.currentSeason.end_date}`}
              </p>
            )}
          </div>
        )}
      </div>

      <div className="rounded-xl bg-[var(--paper-2)] p-5">
        <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider opacity-60">
          Recent Applications
        </h3>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-[var(--line)] text-left">
                <th className="pb-2 font-medium opacity-60">Player</th>
                <th className="pb-2 font-medium opacity-60">Status</th>
                <th className="pb-2 font-medium opacity-60">Season</th>
                <th className="pb-2 font-medium opacity-60">Date</th>
              </tr>
            </thead>
            <tbody>
              {stats.recentApplications.map((app, i) => (
                <tr key={i} className="border-b border-[var(--line)]/50">
                  <td className="py-2 font-medium">{app.minecraft_username}</td>
                  <td className="py-2">
                    <span
                      className={`rounded-full px-2 py-0.5 text-xs font-medium capitalize ${STATUS_COLORS[app.status] ?? ""}`}
                    >
                      {app.status}
                    </span>
                  </td>
                  <td className="py-2 opacity-60">{app.season || "—"}</td>
                  <td className="py-2 opacity-60">{formatDate(app.applied_at)}</td>
                </tr>
              ))}
              {stats.recentApplications.length === 0 && (
                <tr>
                  <td colSpan={4} className="py-4 text-center opacity-40">
                    No applications yet
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function StatCard({
  label,
  value,
  highlight,
}: {
  label: string;
  value: string | number;
  highlight?: boolean;
}) {
  return (
    <div className="rounded-xl bg-[var(--paper-2)] p-4">
      <p className="text-xs font-medium uppercase tracking-wider opacity-60">{label}</p>
      <p className={`mt-1 text-2xl font-bold ${highlight ? "text-orange-500" : ""}`}>
        {value}
      </p>
    </div>
  );
}
