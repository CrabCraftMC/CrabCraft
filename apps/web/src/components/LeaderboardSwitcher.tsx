"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

export default function LeaderboardSwitcher() {
  const pathname = usePathname();
  const isAdvancements = pathname.startsWith("/leaderboard/advancements");
  const isAwards = pathname === "/leaderboard" || (pathname.startsWith("/leaderboard") && !isAdvancements);

  return (
    <h1 className="text-4xl lg:text-5xl font-bold font-mc flex items-center justify-center gap-3 sm:gap-4 flex-wrap">
      {isAwards ? (
        <span className="text-orange-500">Awards</span>
      ) : (
        <Link
          href="/leaderboard"
          className="text-gray-300 dark:text-gray-600 hover:text-orange-500 transition-colors"
        >
          Awards
        </Link>
      )}
      <span
        aria-hidden
        className="text-gray-300 dark:text-gray-700 font-normal text-3xl lg:text-4xl"
      >
        /
      </span>
      {isAdvancements ? (
        <span className="text-orange-500">Advancements</span>
      ) : (
        <Link
          href="/leaderboard/advancements"
          className="text-gray-300 dark:text-gray-600 hover:text-orange-500 transition-colors"
        >
          Advancements
        </Link>
      )}
    </h1>
  );
}
