"use client";

import { useEffect, useMemo, useRef } from "react";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
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

const SceneSkeleton = () => (
  <div className="relative z-10 flex min-h-screen w-full items-center justify-center">
    <span className="font-mc text-xs uppercase tracking-widest text-white/30">
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
    onOpenDashboard: () =>
      window.open(`/wrapped/${data.season}/dashboard`, "_blank"),
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
        `https://starlightskins.lunareclipse.studio/render/walking/${data.playerName}/full`,
      ];
    }
    return [];
  }, [controller.current, data.playerName]);
  usePreloadNextScene(controller.current, nextSceneImages);

  const announcement = `Slide ${controller.current + 1} of ${SCENE_IDS.length}: ${SCENE_TITLES[currentId]}`;

  return (
    <div
      className="fixed inset-0 z-40 overflow-hidden bg-[#0c0a09] text-white"
      style={cursor ? { cursor: `url(${cursor}) 8 8, auto` } : undefined}
    >
      <StoryBackground slide={controller.current} reduced={reduced} />

      <StoryHUD controller={controller} season={data.season} />

      <NavZones controller={controller} />

      <main key={controller.current} className="relative h-full w-full overflow-y-auto">
        <CurrentScene data={data} />
      </main>

      <LiveRegion message={announcement} />

      {/* Hint chip on first slide only, fades after interaction */}
      {controller.current === 0 && !reduced && (
        <p className="pointer-events-none absolute bottom-8 left-1/2 z-40 -translate-x-1/2 animate-pulse text-center font-mc text-[10px] uppercase tracking-[0.3em] text-white/40">
          Tap right · Arrow keys · Swipe
        </p>
      )}
    </div>
  );
}
