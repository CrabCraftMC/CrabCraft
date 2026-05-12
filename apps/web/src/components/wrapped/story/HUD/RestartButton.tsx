"use client";

import { RotateCcw } from "lucide-react";

interface Props {
  onRestart: () => void;
}

export default function RestartButton({ onRestart }: Props) {
  return (
    <button
      type="button"
      onClick={onRestart}
      className="inline-flex items-center gap-1.5 rounded-full border border-white/10 bg-white/5 px-3 py-1.5 text-xs font-bold text-white/70 backdrop-blur-sm transition-colors hover:bg-white/15 hover:text-white"
      aria-label="Restart story from beginning"
      title="Restart (R)"
    >
      <RotateCcw className="h-3.5 w-3.5" aria-hidden />
      <span className="hidden sm:inline">Restart</span>
    </button>
  );
}
