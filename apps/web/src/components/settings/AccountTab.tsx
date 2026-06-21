import Image from "next/image";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";
import type { Application } from "@/lib/types";

function ordinal(n: number): string {
  const s = ["th", "st", "nd", "rd"];
  const v = n % 100;
  return n + (s[(v - 20) % 10] || s[v] || s[0]);
}

const STATUS_STYLES: Record<string, { bg: string; text: string; label: string }> = {
  accepted: { bg: "bg-green-500/20", text: "text-green-400", label: "Accepted" },
  pending: { bg: "bg-yellow-500/20", text: "text-yellow-400", label: "Pending" },
  denied: { bg: "bg-red-500/20", text: "text-red-400", label: "Denied" },
};

function formatDate(timestamp: number): string {
  return new Date(timestamp * 1000).toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

const ROLE_COLORS: Record<string, string> = {
  admin: "bg-red-500/20 text-red-400",
  moderator: "bg-orange-500/20 text-orange-400",
  verified: "bg-green-500/20 text-green-400",
  unverified: "bg-zinc-500/20 text-zinc-400",
};

export default function AccountTab({
  discordUsername,
  avatarUrl,
  minecraftUuid,
  minecraftUsername,
  role,
  createdAt,
  joinRank,
  applications,
}: {
  discordUsername: string;
  avatarUrl: string;
  minecraftUuid: string | null;
  minecraftUsername: string | null;
  role: string;
  createdAt: number | null;
  joinRank: number | null;
  applications: Application[];
}) {
  const joinDate = createdAt
    ? new Date(createdAt * 1000).toLocaleDateString("en-GB", {
        day: "numeric",
        month: "long",
        year: "numeric",
      })
    : null;

  return (
    <div className="space-y-6">
      <h2 className="text-xl font-bold">Account</h2>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        {/* Discord */}
        <Squircle cornerRadius={20} className="bg-[var(--paper-2)] p-4">
          <p className="text-xs font-medium uppercase tracking-wider opacity-60 mb-2">Discord</p>
          <div className="flex items-center gap-3">
            <Image src={avatarUrl} alt="Avatar" width={32} height={32} className="rounded-full" />
            <p className="text-sm font-semibold truncate">{discordUsername}</p>
          </div>
        </Squircle>

        {/* Minecraft */}
        {minecraftUuid && (
          <Squircle cornerRadius={20} className="bg-[var(--paper-2)] p-4">
            <p className="text-xs font-medium uppercase tracking-wider opacity-60 mb-2">Minecraft</p>
            <div className="flex items-center gap-3">
              <PixelIcon
                src={`https://mc-heads.net/avatar/${minecraftUuid}/32.png`}
                alt="Minecraft skin"
                size={32}
                imgClassName="rounded"
              />
              <p className="text-sm font-semibold truncate">{minecraftUsername ?? "Unknown"}</p>
            </div>
          </Squircle>
        )}

        {/* Role */}
        <Squircle cornerRadius={20} className="bg-[var(--paper-2)] p-4">
          <p className="text-xs font-medium uppercase tracking-wider opacity-60 mb-2">Role</p>
          <span className={`inline-block rounded-full px-3 py-1 text-xs font-medium capitalize ${ROLE_COLORS[role] ?? ROLE_COLORS.unverified}`}>
            {role}
          </span>
        </Squircle>

        {/* Member since */}
        {joinDate && (
          <Squircle cornerRadius={20} className="relative bg-[var(--paper-2)] p-4 overflow-hidden">
            {joinRank && (
              <span className="absolute top-1/2 right-3 -translate-y-1/2 text-3xl font-bold text-[var(--foreground)]/[0.07] select-none pointer-events-none">
                {ordinal(joinRank)}
              </span>
            )}
            <p className="text-xs font-medium uppercase tracking-wider opacity-60 mb-2">Member since</p>
            <p className="text-sm font-semibold">{joinDate}</p>
          </Squircle>
        )}
      </div>

      {/* Applications */}
      <h2 className="text-xl font-bold">Your Applications</h2>
      {applications.filter((app) => app.join_reason).length === 0 ? (
        <p className="py-8 text-center opacity-40">No applications found</p>
      ) : (
        <div className="space-y-4">
          {applications
            .filter((app) => app.join_reason)
            .map((app, i) => {
              const style = STATUS_STYLES[app.status] ?? STATUS_STYLES.pending;
              return (
                <Squircle key={i} cornerRadius={20} className="bg-[var(--paper-2)] p-5">
                  <div className="flex flex-wrap items-center gap-3 mb-3">
                    <span className={`inline-flex items-center px-3 py-1 rounded-full text-xs font-bold ${style.bg} ${style.text}`}>
                      {style.label}
                    </span>
                    {app.season && (
                      <span className="text-xs opacity-50">Season {app.season}</span>
                    )}
                    {app.applied_at && (
                      <span className="text-xs opacity-40">
                        Applied {formatDate(app.applied_at)}
                      </span>
                    )}
                  </div>

                  <div className="flex items-center gap-3 mb-3">
                    {app.minecraft_uuid && (
                      <PixelIcon
                        src={`https://mc-heads.net/avatar/${app.minecraft_uuid}/28.png`}
                        alt={app.minecraft_username ?? ""}
                        size={28}
                        imgClassName="rounded"
                      />
                    )}
                    {app.minecraft_username && (
                      <span className="text-sm font-bold">{app.minecraft_username}</span>
                    )}
                    {app.resolved_at && (
                      <span className="text-xs opacity-40 ml-auto">
                        Resolved {formatDate(app.resolved_at)}
                      </span>
                    )}
                  </div>

                  <div className="space-y-2">
                    <div>
                      <p className="text-xs uppercase tracking-wider opacity-50 mb-0.5">Why do you want to join?</p>
                      <p className="text-sm">{app.join_reason}</p>
                    </div>

                    <div className="flex flex-wrap gap-4 text-sm">
                      {app.favourite_wood && (
                        <div>
                          <span className="opacity-50">Favourite wood: </span>
                          <span className="capitalize">{app.favourite_wood}</span>
                        </div>
                      )}
                      <div>
                        <span className="opacity-50">Age requirement: </span>
                        <span className={app.age_met ? "text-green-500" : "text-red-500"}>
                          {app.age_met ? "Yes" : "No"}
                        </span>
                      </div>
                      <div>
                        <span className="opacity-50">Voice chat: </span>
                        <span className={app.voice_chat ? "text-green-500" : "text-red-500"}>
                          {app.voice_chat ? "Yes" : "No"}
                        </span>
                      </div>
                    </div>

                    {app.status === "denied" && app.denial_reason && (
                      <div className="rounded-xl bg-red-500/10 border border-red-500/20 p-3 mt-2">
                        <p className="text-xs text-red-400 uppercase tracking-wider mb-0.5">Denial Reason</p>
                        <p className="text-sm text-red-300">{app.denial_reason}</p>
                      </div>
                    )}
                  </div>
                </Squircle>
              );
            })}
        </div>
      )}
    </div>
  );
}
