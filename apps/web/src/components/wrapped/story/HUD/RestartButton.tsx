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
      className="inline-flex items-center gap-1.5 rounded-full border dark:border-white/10 border-black/10 dark:bg-white/5 bg-black/5 px-3 py-1.5 text-xs font-bold dark:text-white/70 text-stone-600 backdrop-blur-sm transition-colors dark:hover:bg-white/15 hover:bg-black/10 dark:hover:text-stone-100 hover:text-stone-900"
      aria-label="Restart story from beginning"
      title="Restart (R)"
    >
      <RotateCcw className="h-3.5 w-3.5" aria-hidden />
      <span className="hidden sm:inline">Restart</span>
    </button>
  );
}
