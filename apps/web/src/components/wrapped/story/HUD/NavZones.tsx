"use client";

import { useSwipeNav } from "../hooks/useStoryGestures";
import type { StoryController } from "../hooks/useStoryController";
import { SCENE_IDS } from "../data/sceneOrder";

interface Props {
  controller: StoryController;
}

/**
 * Invisible overlay that captures taps (left 25% prev, right 75% next) and
 * pointer drags (50px offset OR 0.5px/ms velocity). Sits above the scene
 * but below the HUD (z-30). The right "next" zone is omitted on the final
 * slide so the scene's own CTAs receive their clicks.
 */
export default function NavZones({ controller }: Props) {
  const handlers = useSwipeNav(controller);
  const isLast = controller.current >= SCENE_IDS.length - 1;

  return (
    <div
      className="pointer-events-none absolute inset-0 z-30"
      // The HUD covers the top strip; navzones reserve the middle 70%.
      style={{ top: "15%", bottom: "10%" }}
    >
      <div
        className="pointer-events-auto absolute inset-y-0 left-0 w-1/4"
        role="button"
        tabIndex={-1}
        aria-label="Previous slide"
        onClick={() => controller.prev()}
        {...handlers}
      />
      {!isLast && (
        <div
          className="pointer-events-auto absolute inset-y-0 right-0 w-3/4"
          role="button"
          tabIndex={-1}
          aria-label="Next slide"
          onClick={() => controller.next()}
          {...handlers}
        />
      )}
    </div>
  );
}
