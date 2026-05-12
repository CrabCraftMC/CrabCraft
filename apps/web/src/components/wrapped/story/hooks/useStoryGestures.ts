"use client";

import { useEffect, useRef } from "react";
import type { StoryController } from "./useStoryController";
import { TOTAL_SCENES } from "../data/sceneOrder";

interface Options {
  controller: StoryController;
  onExit?: () => void;
  onOpenDashboard?: () => void;
}

export function useKeyboardNav({ controller, onExit, onOpenDashboard }: Options) {
  useEffect(() => {
    function handleKey(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === "INPUT" || tag === "TEXTAREA") return;
      switch (e.key) {
        case "ArrowRight":
        case " ":
          e.preventDefault();
          controller.next();
          break;
        case "ArrowLeft":
          e.preventDefault();
          controller.prev();
          break;
        case "Home":
          e.preventDefault();
          controller.jumpTo(0);
          break;
        case "End":
          e.preventDefault();
          controller.jumpTo(TOTAL_SCENES - 1);
          break;
        case "r":
        case "R":
          e.preventDefault();
          controller.jumpTo(0);
          break;
        case "d":
        case "D":
          e.preventDefault();
          onOpenDashboard?.();
          break;
        case "Escape":
          e.preventDefault();
          onExit?.();
          break;
      }
    }
    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  }, [controller, onExit, onOpenDashboard]);
}

export interface DragHandlers {
  onPointerDown: (e: React.PointerEvent) => void;
  onPointerMove: (e: React.PointerEvent) => void;
  onPointerUp: (e: React.PointerEvent) => void;
  onPointerCancel: (e: React.PointerEvent) => void;
}

const OFFSET_THRESHOLD = 50;
const VELOCITY_THRESHOLD = 0.5; // px / ms

export function useSwipeNav(controller: StoryController): DragHandlers {
  const stateRef = useRef<{
    id: number | null;
    startX: number;
    startT: number;
  }>({ id: null, startX: 0, startT: 0 });

  function reset() {
    stateRef.current = { id: null, startX: 0, startT: 0 };
  }

  return {
    onPointerDown(e) {
      if (e.pointerType === "mouse" && e.button !== 0) return;
      stateRef.current = {
        id: e.pointerId,
        startX: e.clientX,
        startT: performance.now(),
      };
    },
    onPointerMove() {
      // No-op; we only care about end position/velocity for snap navigation.
    },
    onPointerUp(e) {
      const s = stateRef.current;
      if (s.id !== e.pointerId) return reset();
      const dx = e.clientX - s.startX;
      const dt = Math.max(1, performance.now() - s.startT);
      const vx = dx / dt;
      if (dx < -OFFSET_THRESHOLD || vx < -VELOCITY_THRESHOLD) {
        controller.next();
      } else if (dx > OFFSET_THRESHOLD || vx > VELOCITY_THRESHOLD) {
        controller.prev();
      }
      reset();
    },
    onPointerCancel() {
      reset();
    },
  };
}
