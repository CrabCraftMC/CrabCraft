import { auth, getAvatarUrl } from "@/lib/auth";
import { getPlayerJoinInfo, getStreamChannelsForUser, getUserApplications } from "@crabcraft/db/queries/web";
import { getPlayerAlts } from "@crabcraft/db/queries/bot";
import SettingsNav from "@/components/settings/SettingsNav";
import AccountTab from "@/components/settings/AccountTab";
import AltsTab from "@/components/settings/AltsTab";
import ChannelsTab from "@/components/settings/ChannelsTab";
import DisplayTab from "@/components/settings/DisplayTab";

const TABS = ["account", "alts", "channels", "display"] as const;
type Tab = (typeof TABS)[number];

export default async function SettingsPage({
  searchParams,
}: {
  searchParams: Promise<{ tab?: string }>;
}) {
  const params = await searchParams;
  const activeTab = (TABS.includes(params.tab as Tab) ? params.tab : "account") as Tab;
  const session = await auth();
  if (!session?.user) return null;
  const user = session.user;

  return (
    <div className="mx-auto w-full max-w-6xl px-3 pt-12 pb-6 sm:px-4 sm:pt-16 sm:pb-8">
      <h1 className="mb-4 text-2xl font-bold sm:mb-6 sm:text-3xl">Settings</h1>

      <SettingsNav />

      <div className="w-full min-w-0">
        {activeTab === "account" && <AccountContent user={user} />}
        {activeTab === "alts" && <AltsContent discordId={user.discordId} minecraftUuid={user.minecraftUuid} />}
        {activeTab === "channels" && <ChannelsContent discordId={user.discordId} />}
        {activeTab === "display" && <DisplayTab />}
      </div>
    </div>
  );
}

async function AccountContent({ user }: { user: { discordId: string; name?: string | null; image?: string | null; minecraftUuid: string | null; minecraftUsername: string | null; role: string } }) {
  const [joinInfo, applications] = await Promise.all([
    getPlayerJoinInfo(user.discordId),
    getUserApplications(user.discordId),
  ]);
  return (
    <AccountTab
      discordUsername={user.name ?? "Unknown"}
      avatarUrl={getAvatarUrl(user)}
      minecraftUuid={user.minecraftUuid}
      minecraftUsername={user.minecraftUsername}
      role={user.role}
      createdAt={joinInfo?.created_at ?? null}
      joinRank={joinInfo?.join_rank ?? null}
      applications={applications}
    />
  );
}

async function AltsContent({ discordId, minecraftUuid }: { discordId: string; minecraftUuid: string | null }) {
  const alts = await getPlayerAlts(discordId);
  return <AltsTab alts={alts} maxAlts={2} minecraftUuid={minecraftUuid} />;
}

async function ChannelsContent({ discordId }: { discordId: string }) {
  const channels = await getStreamChannelsForUser(discordId);
  return <ChannelsTab channels={channels} />;
}
