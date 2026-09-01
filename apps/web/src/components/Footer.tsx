import Link from "next/link";
import config from "@/data/site-config.json";

const exploreLinks = [
  { name: "Home", url: "/" },
  { name: "Gallery", url: "/gallery" },
  { name: "Leaderboard", url: "/leaderboard" },
  { name: "Advancements", url: "/leaderboard/advancements" },
  { name: "Awards", url: "/awards" },
  { name: "Wrapped", url: "/wrapped" },
  { name: "Search players", url: "/search" },
] as const;

const communityLinks = [
  { name: "Apply to join", url: "https://discord.crabcraft.net" },
  { name: "Live map", url: "https://map.crabcraft.net" },
  { name: "Wiki", url: "https://wiki.crabcraft.net" },
] as const;

const iconPaths: Record<string, string> = {
  youtube:
    "M23.5 6.2a3 3 0 0 0-2.1-2.1C19.6 3.6 12 3.6 12 3.6s-7.6 0-9.4.5A3 3 0 0 0 .5 6.2 31.3 31.3 0 0 0 0 12a31.3 31.3 0 0 0 .5 5.8 3 3 0 0 0 2.1 2.1c1.8.5 9.4.5 9.4.5s7.6 0 9.4-.5a3 3 0 0 0 2.1-2.1A31.3 31.3 0 0 0 24 12a31.3 31.3 0 0 0-.5-5.8zM9.6 15.6V8.4l6.3 3.6-6.3 3.6z",
  tiktok:
    "M12.5.5v16.2a3.3 3.3 0 1 1-2.3-3.1V9.8a7.1 7.1 0 1 0 6.1 7v-8a8.6 8.6 0 0 0 5 1.6V6.5a5 5 0 0 1-5-5h-3.8z",
  instagram:
    "M7.8 2h8.4C19.4 2 22 4.6 22 7.8v8.4a5.8 5.8 0 0 1-5.8 5.8H7.8C4.6 22 2 19.4 2 16.2V7.8A5.8 5.8 0 0 1 7.8 2zm-.2 2A3.6 3.6 0 0 0 4 7.6v8.8A3.6 3.6 0 0 0 7.6 20h8.8a3.6 3.6 0 0 0 3.6-3.6V7.6A3.6 3.6 0 0 0 16.4 4H7.6zm9.65 1.5a1.25 1.25 0 1 1 0 2.5 1.25 1.25 0 0 1 0-2.5zM12 7a5 5 0 1 1 0 10 5 5 0 0 1 0-10zm0 2a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
  discord:
    "M20.3 4.4A19.6 19.6 0 0 0 15.4 3c-.2.4-.5 1-.7 1.4a18.2 18.2 0 0 0-5.4 0C9.1 4 8.8 3.4 8.6 3A19.5 19.5 0 0 0 3.7 4.4 20.2 20.2 0 0 0 .2 17.2a19.7 19.7 0 0 0 6 3 14.3 14.3 0 0 0 1.2-2 12.8 12.8 0 0 1-2-.9l.5-.4a14 14 0 0 0 12.1 0l.5.4a12.8 12.8 0 0 1-2 .9 14.3 14.3 0 0 0 1.3 2 19.7 19.7 0 0 0 6-3A20.2 20.2 0 0 0 20.3 4.4zM8 14.7c-1.1 0-2-1-2-2.3s.9-2.3 2-2.3 2 1 2 2.3-.9 2.3-2 2.3zm8 0c-1.1 0-2-1-2-2.3s.9-2.3 2-2.3 2 1 2 2.3-.9 2.3-2 2.3z",
};

const socialLabels: Record<string, string> = {
  youtube: "CrabCraft on YouTube",
  tiktok: "CrabCraft on TikTok",
  instagram: "CrabCraft on Instagram",
  discord: "CrabCraft Discord",
};

export default function Footer() {
  return (
    <footer className="pt-16 pb-8 text-gray-900 dark:text-gray-100 relative overflow-hidden transition-colors">
      <div className="container mx-auto px-4">
        <div className="border-t border-gray-300 pt-8 dark:border-[#3d3028]">
          <div className="grid gap-10 sm:grid-cols-2 lg:grid-cols-[0.9fr_1.6fr_0.8fr] lg:gap-12">
            <nav aria-label="Explore">
              <h2 className="text-xs font-bold uppercase tracking-[0.18em] text-orange-500">
                Explore
              </h2>
              <ul className="mt-4 space-y-2.5">
                {exploreLinks.map((link) => (
                  <li key={link.url}>
                    <Link
                      href={link.url}
                      className="text-sm text-gray-600 transition-colors hover:text-orange-500 dark:text-gray-400"
                    >
                      {link.name}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>

            <nav aria-label="Minecraft tools">
              <h2 className="text-xs font-bold uppercase tracking-[0.18em] text-orange-500">
                Minecraft tools
              </h2>
              <ul className="mt-4 grid grid-cols-1 gap-x-6 gap-y-2.5 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
                {config.navbar.tools.map((tool) => (
                  <li key={tool.url}>
                    <Link
                      href={tool.url}
                      className="text-sm text-gray-600 transition-colors hover:text-orange-500 dark:text-gray-400"
                    >
                      {tool.name}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>

            <nav aria-label="Community">
              <h2 className="text-xs font-bold uppercase tracking-[0.18em] text-orange-500">
                Community
              </h2>
              <ul className="mt-4 space-y-2.5">
                {communityLinks.map((link) => (
                  <li key={link.url}>
                    <a
                      href={link.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      data-umami-event="community-link-opened"
                      data-umami-event-destination={link.name}
                      data-umami-event-location="footer"
                      className="text-sm text-gray-600 transition-colors hover:text-orange-500 dark:text-gray-400"
                    >
                      {link.name}
                      <span className="sr-only"> (opens in a new tab)</span>
                    </a>
                  </li>
                ))}
              </ul>
            </nav>
          </div>

          <div className="mt-10 flex flex-col items-center justify-between gap-5 text-sm text-gray-600 dark:text-gray-400 md:flex-row">
            <div className="text-center md:text-left">
              <p className="font-semibold">
                <span className="text-orange-500">{config.site.name}</span>{" "}
                &copy; {new Date().getFullYear()}.
              </p>
              <p className="mt-1 max-w-2xl text-xs">{config.site.disclaimer}</p>
            </div>
            <div className="flex flex-col items-center gap-2 md:items-end">
              {process.env.GIT_COMMIT_SHA && (
                <p className="text-xs text-gray-400 dark:text-gray-600 font-mono">
                  {process.env.GIT_COMMIT_SHA.slice(0, 7)}
                </p>
              )}
              <div className="flex gap-4">
                {config.navbar.socials.map(
                  (social: { platform: string; url: string }) => (
                    <a
                      key={social.platform}
                      href={social.url}
                      target="_blank"
                      rel="noopener noreferrer"
                      aria-label={socialLabels[social.platform] ?? social.platform}
                      data-umami-event="social-link-opened"
                      data-umami-event-platform={social.platform}
                      data-umami-event-location="footer"
                      className="text-gray-400 dark:text-gray-500 hover:text-orange-500 transition-colors"
                    >
                      <svg
                        xmlns="http://www.w3.org/2000/svg"
                        className="w-5 h-5"
                        viewBox="0 0 24 24"
                        fill="currentColor"
                        aria-hidden="true"
                      >
                        <path d={iconPaths[social.platform] || ""} />
                      </svg>
                    </a>
                  )
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </footer>
  );
}
