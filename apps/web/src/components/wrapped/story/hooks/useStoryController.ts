"use client";

import { useCallback, useMemo, useRef, useState } from "react";
import { TOTAL_SCENES } from "../data/sceneOrder";

export type Direction = -1 | 1;
export type Phase = "active" | "transitioning";

export interface StoryController {
  current: number;
  direction: Direction;
  phase: Phase;
  visited: ReadonlySet<number>;
  next: () => void;
  prev: () => void;
  jumpTo: (index: number) => void;
  setPhase: (p: Phase) => void;
}

export function useStoryController(initial = 0): StoryController {
  const [current, setCurrent] = useState(initial);
  const [direction, setDirection] = useState<Direction>(1);
  const [phase, setPhase] = useState<Phase>("active");
  const visitedRef = useRef<Set<number>>(new Set([initial]));
  const lockRef = useRef(false);

  const guard = useCallback(() => {
    if (lockRef.current) return false;
    lockRef.current = true;
    // Release the lock on the next frame so the new scene has mounted.
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        lockRef.current = false;
      });
    });
    return true;
  }, []);

  const next = useCallback(() => {
    if (!guard()) return;
    setCurrent((c) => {
      const target = Math.min(c + 1, TOTAL_SCENES - 1);
      if (target !== c) visitedRef.current.add(target);
      return target;
    });
    setDirection(1);
  }, [guard]);

  const prev = useCallback(() => {
    if (!guard()) return;
    setCurrent((c) => Math.max(c - 1, 0));
    setDirection(-1);
  }, [guard]);

  const jumpTo = useCallback(
    (index: number) => {
      if (!guard()) return;
      const clamped = Math.max(0, Math.min(TOTAL_SCENES - 1, index));
      setDirection(clamped >= current ? 1 : -1);
      setCurrent(clamped);
      visitedRef.current.add(clamped);
    },
    [current, guard]
  );

  return useMemo(
    () => ({
      current,
      direction,
      phase,
      visited: visitedRef.current,
      next,
      prev,
      jumpTo,
      setPhase,
    }),
    [current, direction, phase, next, prev, jumpTo]
  );
}
