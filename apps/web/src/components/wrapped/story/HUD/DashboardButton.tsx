"use client";

import Link from "next/link";
import { LayoutGrid } from "lucide-react";

interface Props {
  season: string;
}

export default function DashboardButton({ season }: Props) {
  return (
    <Link
      href={`/wrapped/${season}/dashboard`}
      className="inline-flex items-center gap-1.5 rounded-full border border-white/15 bg-white/5 px-3 py-1.5 text-xs font-bold text-white/80 backdrop-blur-sm transition-colors hover:bg-white/15 hover:text-white"
      aria-label="Open detailed dashboard view"
      title="Dashboard (D)"
    >
      <LayoutGrid className="h-3.5 w-3.5" aria-hidden />
      <span className="hidden sm:inline">Dashboard</span>
    </Link>
  );
}
