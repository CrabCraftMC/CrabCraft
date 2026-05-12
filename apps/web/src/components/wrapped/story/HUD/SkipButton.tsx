"use client";

import { FastForward } from "lucide-react";

interface Props {
  onSkip: () => void;
}

export default function SkipButton({ onSkip }: Props) {
  return (
    <button
      type="button"
      onClick={onSkip}
      className="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-xs font-bold text-white/70 backdrop-blur-sm transition-colors hover:bg-white/15 hover:text-white"
      aria-label="Skip to summary"
      title="Skip to summary (End)"
    >
      <FastForward className="h-3.5 w-3.5" aria-hidden />
      <span className="hidden sm:inline">Skip</span>
    </button>
  );
}
