"use client";

import { useMemo } from "react";
import { useWebHaptics } from "web-haptics/react";
import { useReducedMotion } from "./useReducedMotion";

export interface Haptics {
  tick: () => void;
  light: () => void;
  medium: () => void;
  heavy: () => void;
}

/**
 * Story-flow haptics wrapper. The underlying `useWebHaptics()` exposes a
 * single `trigger()` call — intensity is signalled by repeating the trigger
 * for heavier feedback. Suppressed when `prefers-reduced-motion: reduce`.
 */
export function useHaptics(): Haptics {
  const { trigger } = useWebHaptics();
  const reduced = useReducedMotion();

  return useMemo(() => {
    const guarded = (times: number) => () => {
      if (reduced) return;
      for (let i = 0; i < times; i++) trigger();
    };
    return {
      tick: guarded(1),
      light: guarded(1),
      medium: guarded(2),
      heavy: guarded(3),
    };
  }, [trigger, reduced]);
}
