"use client";

import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { TOTAL_SCENES } from "../data/sceneOrder";
import type { StoryController } from "../hooks/useStoryController";
import SegmentedProgress from "./SegmentedProgress";
import SlideCounter from "./SlideCounter";
import SkipButton from "./SkipButton";
import RestartButton from "./RestartButton";

interface Props {
  controller: StoryController;
}

export default function StoryHUD({ controller }: Props) {
  const isLast = controller.current === TOTAL_SCENES - 1;

  return (
    <header
      className="pointer-events-none fixed inset-x-0 top-0 z-50"
      style={{ paddingTop: "env(safe-area-inset-top)" }}
    >
      <div className="pointer-events-auto mx-auto flex max-w-6xl items-center gap-3 px-3 py-3 sm:gap-4 sm:px-6 sm:py-4">
        <Link
          href="/wrapped"
          className="flex items-center gap-1 rounded-full border dark:border-white/10 border-black/10 dark:bg-white/5 bg-black/5 px-2.5 py-1.5 text-xs font-bold dark:text-white/70 text-stone-600 backdrop-blur-sm transition-colors dark:hover:bg-white/15 hover:bg-black/10 dark:hover:text-stone-100 hover:text-stone-900"
          aria-label="Back to season selector"
          title="Back to seasons (Esc)"
        >
          <ChevronLeft className="h-3.5 w-3.5" aria-hidden />
          <span className="hidden sm:inline">Seasons</span>
        </Link>

        <SegmentedProgress current={controller.current} total={TOTAL_SCENES} />

        <SlideCounter current={controller.current} total={TOTAL_SCENES} />

        {isLast ? (
          <RestartButton onRestart={() => controller.jumpTo(0)} />
        ) : (
          <SkipButton onSkip={() => controller.jumpTo(TOTAL_SCENES - 1)} />
        )}
      </div>
    </header>
  );
}
