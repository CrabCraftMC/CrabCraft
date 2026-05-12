import Link from "next/link";
import type { WrappedErrorKind } from "@/lib/wrappedTypes";

const MESSAGES: Record<WrappedErrorKind, { title: string; body: string }> = {
  "no-mc": {
    title: "No Minecraft account linked",
    body: "Your Discord account needs a linked Minecraft UUID",
  },
  "no-data": {
    title: "No data for this season",
    body: "You don't have stats recorded for this season",
  },
  "fetch-error": {
    title: "Something went wrong",
    body: "Please try again later",
  },
};

export default function WrappedErrorView({ kind }: { kind: WrappedErrorKind }) {
  const { title, body } = MESSAGES[kind];
  return (
    <div className="min-h-screen flex items-center justify-center pt-24">
      <div className="text-center">
        <h1 className="text-2xl font-bold text-gray-800 dark:text-gray-200">
          {title}
        </h1>
        <p className="text-gray-500 dark:text-gray-400 mt-2">{body}</p>
        <Link
          href="/wrapped"
          className="inline-block mt-4 text-orange-500 hover:underline"
        >
          Back to seasons
        </Link>
      </div>
    </div>
  );
}
