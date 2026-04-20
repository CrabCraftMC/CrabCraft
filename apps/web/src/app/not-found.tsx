import Link from "next/link";

export default function NotFound() {
  return (
    <div className="flex-1 flex items-center justify-center px-4">
      <div className="text-center">
        <p className="font-mc text-8xl lg:text-9xl text-orange-500">404</p>
        <h1 className="text-2xl lg:text-3xl font-bold text-gray-800 dark:text-gray-200 mt-4">
          Page not found
        </h1>
        <p className="text-gray-500 dark:text-gray-400 mt-2 max-w-md mx-auto">
          Looks like you&apos;ve wandered into unloaded chunks. This page doesn&apos;t exist.
        </p>
        <div className="flex gap-3 justify-center mt-6">
          <Link
            href="/"
            className="bg-orange-500 hover:bg-orange-600 text-white font-bold py-2.5 px-6 rounded-full transition-colors text-sm"
          >
            Go Home
          </Link>
          <Link
            href="/leaderboard"
            className="bg-gray-200 dark:bg-[#2a221b] hover:bg-gray-300 dark:hover:bg-[#3d3028] text-gray-700 dark:text-gray-300 font-bold py-2.5 px-6 rounded-full transition-colors text-sm"
          >
            Leaderboard
          </Link>
        </div>
      </div>
    </div>
  );
}
