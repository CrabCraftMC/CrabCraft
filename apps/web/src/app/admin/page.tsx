import Link from "next/link";
import { auth } from "@/lib/auth";
import { getOverviewStats, getSeasons, getAdminUsers, getAllApplications } from "@crabcraft/db/queries/web";
import OverviewTab from "@/components/admin/OverviewTab";
import SeasonsTab from "@/components/admin/SeasonsTab";
import PlayersTab from "@/components/admin/PlayersTab";
import ApplicationsTab from "@/components/admin/ApplicationsTab";

const TABS = ["overview", "seasons", "players", "applications"] as const;
type Tab = (typeof TABS)[number];

export default async function AdminPage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string; status?: string; season?: string; q?: string; page?: string }>;
}) {
  const [params, session] = await Promise.all([searchParams, auth()]);
  const isAdmin = session?.user?.role === "admin";
  const activeTab = (TABS.includes(params.tab as Tab) ? params.tab : "overview") as Tab;
  const page = Math.max(1, parseInt(params.page ?? "1", 10) || 1);

  return (
    <div className="mx-auto w-full max-w-6xl px-3 py-6 sm:px-4 sm:py-8">
      <h1 className="mb-4 text-2xl font-bold sm:mb-6 sm:text-3xl">Admin Panel</h1>

      <nav className="mb-6 flex gap-1 overflow-x-auto rounded-xl bg-[var(--paper-2)] p-1 sm:mb-8">
        {TABS.map((tab) => (
          <Link
            key={tab}
            href={`/admin?tab=${tab}`}
            className={`shrink-0 rounded-lg px-3 py-1.5 text-xs font-medium capitalize transition-colors sm:px-4 sm:py-2 sm:text-sm ${
              activeTab === tab
                ? "bg-orange-500 text-white shadow-sm"
                : "text-[var(--foreground)]/60 hover:bg-[var(--paper)] hover:text-[var(--foreground)]"
            }`}
          >
            {tab}
          </Link>
        ))}
      </nav>

      <div className="w-full min-w-0">
        {activeTab === "overview" && <OverviewContent />}
        {activeTab === "seasons" && <SeasonsContent isAdmin={isAdmin} />}
        {activeTab === "players" && <PlayersContent search={params.q} page={page} isAdmin={isAdmin} />}
        {activeTab === "applications" && (
          <ApplicationsContent status={params.status} season={params.season} />
        )}
      </div>
    </div>
  );
}

async function OverviewContent() {
  const stats = await getOverviewStats();
  return <OverviewTab stats={stats} />;
}

async function SeasonsContent({ isAdmin }: { isAdmin: boolean }) {
  const seasons = await getSeasons();
  return <SeasonsTab seasons={seasons} isAdmin={isAdmin} />;
}

async function PlayersContent({ search, page, isAdmin }: { search?: string; page: number; isAdmin: boolean }) {
  const allPlayers = await getAdminUsers();
  const filtered = search
    ? allPlayers.filter(
        (p) =>
          p.minecraft_username?.toLowerCase().includes(search.toLowerCase()) ||
          p.discord_username.toLowerCase().includes(search.toLowerCase()),
      )
    : allPlayers;
  const perPage = 50;
  const totalPages = Math.ceil(filtered.length / perPage);
  const paginated = filtered.slice((page - 1) * perPage, page * perPage);
  return <PlayersTab players={paginated} search={search} page={page} totalPages={totalPages} totalCount={filtered.length} isAdmin={isAdmin} />;
}

async function ApplicationsContent({
  status,
  season,
}: {
  status?: string;
  season?: string;
}) {
  const [apps, seasons] = await Promise.all([
    getAllApplications({
      status: status || undefined,
      season: season || undefined,
      limit: 100,
    }),
    getSeasons(),
  ]);
  return <ApplicationsTab applications={apps} seasons={seasons} currentStatus={status} currentSeason={season} />;
}
