"use client";

import { useEffect } from "react";
import { TOTAL_SCENES } from "../data/sceneOrder";

/**
 * Preload images for the upcoming scene on idle. Scene code chunks themselves
 * are pulled in by `next/dynamic` when React mounts the next scene; this hook
 * focuses on static image assets that should be cached before the timeline
 * starts.
 */
export function usePreloadNextScene(current: number, images: string[]) {
  useEffect(() => {
    if (current >= TOTAL_SCENES - 1 || images.length === 0) return;

    const ric =
      typeof window !== "undefined" &&
      "requestIdleCallback" in window &&
      typeof window.requestIdleCallback === "function"
        ? window.requestIdleCallback.bind(window)
        : null;

    let timeoutHandle: ReturnType<typeof setTimeout> | null = null;
    let idleHandle: number | null = null;

    const run = () => {
      for (const src of images) {
        const img = new Image();
        img.src = src;
      }
    };

    if (ric) {
      idleHandle = ric(run, { timeout: 1500 });
    } else {
      timeoutHandle = setTimeout(run, 200);
    }

    return () => {
      if (timeoutHandle) clearTimeout(timeoutHandle);
      if (idleHandle !== null && typeof window !== "undefined" && "cancelIdleCallback" in window) {
        window.cancelIdleCallback(idleHandle);
      }
    };
  }, [current, images]);
}
