"use client";

import { useEffect, useRef, useState } from "react";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import { useHaptics } from "../hooks/useHaptics";

interface Props {
  value: number;
  duration?: number;
  delay?: number;
  ease?: string;
  className?: string;
  prefix?: string;
  suffix?: string;
  /** Emit a haptic tick at evenly-spaced milestones during the count. */
  haptic?: boolean;
  /** Approximate number of haptic pulses spread across the count. */
  hapticTicks?: number;
  onComplete?: () => void;
}

/**
 * Tweens a counter from 0 → value with GSAP overshoot. Uses `Intl.NumberFormat`
 * to render localized digits each frame. When reduced motion is on, jumps
 * straight to the final value.
 */
export default function DigitRoller({
  value,
  duration = 1.6,
  delay = 0,
  ease = "crab-overshoot",
  className,
  prefix = "",
  suffix = "",
  haptic = true,
  hapticTicks = 16,
  onComplete,
}: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const [displayed, setDisplayed] = useState<number>(0);
  const reduced = useReducedMotion();
  const haptics = useHaptics();

  useEffect(() => {
    if (reduced) {
      setDisplayed(value);
      onComplete?.();
      return;
    }
    const obj = { v: 0 };
    // Bound the haptic cadence: ~`hapticTicks` pulses regardless of magnitude.
    const step =
      haptic && value !== 0
        ? Math.max(1, Math.ceil(Math.abs(value) / hapticTicks))
        : 0;
    let lastTicked = 0;
    const tween = gsap.to(obj, {
      v: value,
      duration,
      delay,
      ease,
      onUpdate: () => {
        const rounded = Math.round(obj.v);
        setDisplayed(rounded);
        if (step > 0 && Math.abs(rounded - lastTicked) >= step) {
          lastTicked = rounded;
          haptics.tick();
        }
      },
      onComplete,
    });
    return () => {
      tween.kill();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, duration, delay, ease, reduced]);

  return (
    <span ref={ref} className={`tabular-nums ${className ?? ""}`}>
      {prefix}
      {displayed.toLocaleString()}
      {suffix}
    </span>
  );
}
