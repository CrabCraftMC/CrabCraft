"use client";

import { useTransition } from "react";
import { useRouter } from "next/navigation";
import { hiddenPlayersUrl } from "@/lib/leaderboardVisibility";

export default function LeaderboardVisibilityToggle({
  showHidden,
}: {
  showHidden: boolean;
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();

  return (
    <label
      className="inline-flex min-h-9 items-center gap-2.5 cursor-pointer text-xs text-gray-500 dark:text-gray-400 animate-in"
      style={{ animationDelay: "0.25s" }}
      aria-busy={pending}
    >
      <span>Show hidden players</span>
      <input
        type="checkbox"
        role="switch"
        aria-label="Show hidden players"
        checked={showHidden}
        disabled={pending}
        onChange={(event) => {
          const url = hiddenPlayersUrl(window.location.pathname, window.location.search, event.target.checked);
          startTransition(() => router.replace(url, { scroll: false }));
        }}
        className="peer sr-only"
      />
      <span aria-hidden="true" className="relative h-5 w-9 shrink-0 rounded-full bg-gray-300 dark:bg-gray-600 transition-colors peer-checked:bg-orange-500 peer-focus-visible:outline-2 peer-focus-visible:outline-offset-4 peer-focus-visible:outline-orange-500 peer-disabled:opacity-50 after:absolute after:top-0.5 after:left-0.5 after:h-4 after:w-4 after:rounded-full after:bg-white after:shadow-sm after:transition-transform peer-checked:after:translate-x-4" />
    </label>
  );
}
