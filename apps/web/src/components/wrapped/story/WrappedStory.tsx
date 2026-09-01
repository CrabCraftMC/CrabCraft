"use client";

import { useEffect, useMemo, useRef } from "react";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
import { AnimatePresence, motion } from "framer-motion";
import { useStoryController } from "./hooks/useStoryController";
import { useKeyboardNav } from "./hooks/useStoryGestures";
import { useHaptics } from "./hooks/useHaptics";
import { usePreloadNextScene } from "./hooks/usePreloadNextScene";
import { useReducedMotion } from "./hooks/useReducedMotion";
import StoryHUD from "./HUD/StoryHUD";
import NavZones from "./HUD/NavZones";
import LiveRegion from "./HUD/LiveRegion";
import StoryBackground from "./background/StoryBackground";
import {
  SCENE_IDS,
  SCENE_TITLES,
  SLIDE_CURSORS,
  type SceneId,
} from "./data/sceneOrder";
import type { WrappedData } from "@/lib/wrappedTypes";

const SCENE_TRANSITION_VARIANTS = {
  enter: (direction: number) => ({ x: `${direction * 100}%`, opacity: 0 }),
  center: { x: "0%", opacity: 1 },
  exit: (direction: number) => ({ x: `${-direction * 100}%`, opacity: 0 }),
};

const SceneSkeleton = () => (
  <div className="relative z-10 flex min-h-screen w-full items-center justify-center">
    <span className="text-xs uppercase tracking-widest dark:text-white/30 text-stone-400">
      Loading...
    </span>
  </div>
);

const SCENE_COMPONENTS: Record<
  SceneId,
  React.ComponentType<{ data: WrappedData }>
> = {
  intro: dynamic(() => import("./scenes/IntroScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
  playtime: dynamic(() => import("./scenes/PlaytimeScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
  distance: dynamic(() => import("./scenes/DistanceScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
  mining: dynamic(() => import("./scenes/MiningScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
  combat: dynamic(() => import("./scenes/CombatScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
  building: dynamic(() => import("./scenes/BuildingScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
  "fun-facts": dynamic(() => import("./scenes/FunFactsScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
  rankings: dynamic(() => import("./scenes/RankingsScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
  summary: dynamic(() => import("./scenes/SummaryScene"), {
    ssr: false,
    loading: SceneSkeleton,
  }),
};

export default function WrappedStory({ data }: { data: WrappedData }) {
  const router = useRouter();
  const controller = useStoryController(0);
  const haptics = useHaptics();
  const reduced = useReducedMotion();
  const previousIndex = useRef(controller.current);

  const currentId = SCENE_IDS[controller.current];
  const CurrentScene = SCENE_COMPONENTS[currentId];
  const cursor = SLIDE_CURSORS[controller.current];

  useKeyboardNav({
    controller,
    onExit: () => router.push("/wrapped"),
  });

  useEffect(() => {
    if (previousIndex.current !== controller.current) {
      previousIndex.current = controller.current;
      haptics.light();
    }
  }, [controller.current, haptics]);

  const nextSceneImages = useMemo(() => {
    // Preload the player skin used by the Intro/Summary scenes.
    if (controller.current === 0 || controller.current === 7) {
      return [
        `https://mc-api.io/render/full/${data.playerUuid}`,
      ];
    }
    return [];
  }, [controller.current, data.playerUuid]);
  usePreloadNextScene(controller.current, nextSceneImages);

  const announcement = `Slide ${controller.current + 1} of ${SCENE_IDS.length}: ${SCENE_TITLES[currentId]}`;

  return (
    <div
      className="fixed inset-x-0 top-20 bottom-4 z-40 px-4 md:top-28 md:bottom-6 lg:px-8"
      style={cursor ? { cursor: `url(${cursor}) 8 8, auto` } : undefined}
    >
      <div className="container relative mx-auto h-full overflow-hidden rounded-3xl bg-stone-50 text-stone-800 ring-1 ring-black/10 dark:bg-[#1a1614] dark:text-stone-100 dark:ring-white/10">
        <StoryBackground slide={controller.current} reduced={reduced} />

        <StoryHUD controller={controller} />

        <NavZones controller={controller} />

        <main className="relative h-full w-full overflow-hidden">
          <AnimatePresence mode="popLayout" custom={controller.direction} initial={false}>
            <motion.div
              key={controller.current}
              custom={controller.direction}
              variants={SCENE_TRANSITION_VARIANTS}
              initial={reduced ? false : "enter"}
              animate="center"
              exit={reduced ? undefined : "exit"}
              transition={
                reduced
                  ? { duration: 0 }
                  : { x: { type: "tween", duration: 0.45, ease: [0.32, 0.72, 0.24, 1] }, opacity: { duration: 0.25 } }
              }
              className="absolute inset-0"
            >
              <CurrentScene data={data} />
            </motion.div>
          </AnimatePresence>
        </main>

        <LiveRegion message={announcement} />

        {/* Hint chip on first slide only, fades after interaction */}
        {controller.current === 0 && !reduced && (
          <p className="pointer-events-none absolute bottom-8 left-1/2 z-40 -translate-x-1/2 animate-pulse text-center text-[10px] uppercase tracking-[0.3em] dark:text-white/40 text-stone-500">
            Tap right · Arrow keys · Swipe
          </p>
        )}
      </div>
    </div>
  );
}
