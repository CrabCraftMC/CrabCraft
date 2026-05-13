"use client";

import { useEffect, useRef, useState } from "react";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";

interface Props {
  value: number;
  duration?: number;
  delay?: number;
  ease?: string;
  className?: string;
  prefix?: string;
  suffix?: string;
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
  onComplete,
}: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const [displayed, setDisplayed] = useState<number>(0);
  const reduced = useReducedMotion();

  useEffect(() => {
    if (reduced) {
      setDisplayed(value);
      onComplete?.();
      return;
    }
    const obj = { v: 0 };
    const tween = gsap.to(obj, {
      v: value,
      duration,
      delay,
      ease,
      onUpdate: () => setDisplayed(Math.round(obj.v)),
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
