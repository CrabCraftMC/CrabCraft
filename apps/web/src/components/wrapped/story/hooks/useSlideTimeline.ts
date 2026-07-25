"use client";

import { useGSAP } from "@gsap/react";
import type { RefObject } from "react";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "./useReducedMotion";

type TimelineBuilder = (gsap: typeof import("gsap").gsap, reduced: boolean) => void;

interface Options {
  scope: RefObject<HTMLElement | null>;
  /**
   * Re-run the builder when these values change. Defaults to []; the builder
   * runs once on mount and reverts cleanly on unmount.
   */
  dependencies?: unknown[];
}

/**
 * Per-scene GSAP entry point. Auto-reverts on unmount (React 19 strict-mode
 * safe via @gsap/react). When `prefers-reduced-motion: reduce` is set, the
 * builder receives `reduced=true` and should apply `gsap.set` final states
 * rather than tweening.
 */
export function useSlideTimeline(builder: TimelineBuilder, opts: Options) {
  const reduced = useReducedMotion();
  useGSAP(
    () => {
      builder(gsap, reduced);
    },
    { scope: opts.scope, dependencies: [reduced, ...(opts.dependencies ?? [])] }
  );
}
