import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/lib/auth";
import { getUserApplications } from "@/lib/queries";
import type { Application } from "@/lib/types";
import { formatUnixDate as formatDate } from "@/lib/formatUnixDate";
import PixelIcon from "@/components/PixelIcon";
import Squircle from "@/components/Squircle";

export const metadata: Metadata = {
  title: "Your Applications",
  description: "View your CrabCraft applications.",
};

const statusStyles: Record<
  string,
  { bg: string; text: string; label: string }
> = {
  accepted: {
    bg: "bg-green-500/20",
    text: "text-green-400",
    label: "Accepted",
  },
  pending: {
    bg: "bg-yellow-500/20",
    text: "text-yellow-400",
    label: "Pending",
  },
  denied: { bg: "bg-red-500/20", text: "text-red-400", label: "Denied" },
};

export default async function ApplicationsPage() {
  const session = await auth();

  if (!session) {
    redirect("/login");
  }

  const user = session.user;
  let applications: Application[] = [];
  let error = "";

  try {
    applications = await getUserApplications(user.discordId);
  } catch (e) {
    console.error("Failed to fetch applications:", e);
    error = "fetch-error";
  }

  return (
    <div className="pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-3xl">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">
            Your Applications
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            View your CrabCraft membership applications
          </p>
        </div>

        {error === "fetch-error" ? (
          <div className="text-center py-16">
            <h2 className="text-xl font-bold text-gray-800 dark:text-gray-200">
              Something went wrong
            </h2>
            <p className="text-gray-500 dark:text-gray-400 mt-2">
              Please try again later
            </p>
          </div>
        ) : applications.length === 0 ? (
          <div className="text-center py-16">
            <h2 className="text-xl font-bold text-gray-800 dark:text-gray-200">
              No applications found
            </h2>
            <p className="text-gray-500 dark:text-gray-400 mt-2">
              You haven&apos;t submitted any applications yet
            </p>
            <a
              href="https://discord.crabcraft.net"
              target="_blank"
              rel="noopener noreferrer"
              data-umami-event="community-link-opened"
              data-umami-event-destination="Discord"
              data-umami-event-location="applications-empty-state"
              className="inline-block mt-4 text-orange-500 hover:underline font-bold"
            >
              Apply via Discord &rarr;
            </a>
          </div>
        ) : (
          <div className="space-y-4">
            {applications
              .filter((app) => app.join_reason)
              .map((app, i) => {
                const style =
                  statusStyles[app.status] || statusStyles.pending;
                return (
                  <Squircle
                    cornerRadius={32}
                    key={i}
                    className="bg-paper-2/60 backdrop-blur-sm p-6 animate-in"
                  >
                    <div className="flex flex-wrap items-center gap-3 mb-4">
                      <span
                        className={`inline-flex items-center px-3 py-1 rounded-full text-xs font-bold ${style.bg} ${style.text}`}
                      >
                        {style.label}
                      </span>
                      {app.season && (
                        <span className="text-sm text-gray-500 dark:text-gray-400">
                          Season {app.season}
                        </span>
                      )}
                      {app.applied_at && (
                        <span className="text-sm text-gray-400 dark:text-gray-500">
                          Applied {formatDate(app.applied_at)}
                        </span>
                      )}
                    </div>

                    <div className="flex items-center gap-3 mb-4">
                      {app.minecraft_uuid && (
                        <PixelIcon
                          src={`https://mc-heads.net/avatar/${app.minecraft_uuid}/32.png`}
                          alt={app.minecraft_username ?? ""}
                          size={32}
                          imgClassName="rounded"
                        />
                      )}
                      {app.minecraft_username && (
                        <span className="font-bold text-gray-800 dark:text-gray-200">
                          {app.minecraft_username}
                        </span>
                      )}
                      {app.resolved_at && (
                        <span className="text-xs text-gray-400 dark:text-gray-500 ml-auto">
                          Resolved {formatDate(app.resolved_at)}
                        </span>
                      )}
                    </div>

                    <div className="space-y-3">
                      <div>
                        <p className="text-xs text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-1">
                          Why do you want to join?
                        </p>
                        <p className="text-sm text-gray-700 dark:text-gray-300">
                          {app.join_reason}
                        </p>
                      </div>

                      <div className="flex flex-wrap gap-4 text-sm">
                        {app.favourite_wood && (
                          <div>
                            <span className="text-gray-500 dark:text-gray-400">
                              Favourite wood:{" "}
                            </span>
                            <span className="text-gray-700 dark:text-gray-300 capitalize">
                              {app.favourite_wood}
                            </span>
                          </div>
                        )}
                        <div>
                          <span className="text-gray-500 dark:text-gray-400">
                            Age requirement:{" "}
                          </span>
                          <span
                            className={
                              app.age_met ? "text-green-500" : "text-red-500"
                            }
                          >
                            {app.age_met ? "Yes" : "No"}
                          </span>
                        </div>
                        <div>
                          <span className="text-gray-500 dark:text-gray-400">
                            Voice chat:{" "}
                          </span>
                          <span
                            className={
                              app.voice_chat
                                ? "text-green-500"
                                : "text-red-500"
                            }
                          >
                            {app.voice_chat ? "Yes" : "No"}
                          </span>
                        </div>
                      </div>

                      {app.status === "denied" && app.denial_reason && (
                        <div className="rounded-xl bg-red-500/10 border border-red-500/20 p-4">
                          <p className="text-xs text-red-400 uppercase tracking-wider mb-1">
                            Denial Reason
                          </p>
                          <p className="text-sm text-red-300">
                            {app.denial_reason}
                          </p>
                        </div>
                      )}
                    </div>
                  </Squircle>
                );
              })}
          </div>
        )}

        <div className="text-left pt-8">
          <Link
            href="/"
            className="text-sm text-gray-400 dark:text-gray-500 hover:text-orange-500 transition-colors"
          >
            &larr; Back to home
          </Link>
        </div>
      </div>
    </div>
  );
}
