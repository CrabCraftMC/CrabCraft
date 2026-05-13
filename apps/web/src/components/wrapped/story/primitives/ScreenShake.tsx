"use client";

import { useGSAP } from "@gsap/react";
import { useRef, type ReactNode } from "react";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";

interface Props {
  children: ReactNode;
  /** When this value changes, the shake fires. */
  trigger: unknown;
  delay?: number;
  intensity?: number;
  duration?: number;
  className?: string;
}

/**
 * Shakes its children violently with a randomized x/y jitter for `duration`
 * seconds, then settles back to 0. Honors reduced motion.
 */
export default function ScreenShake({
  children,
  trigger,
  delay = 0,
  intensity = 8,
  duration = 0.5,
  className,
}: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const reduced = useReducedMotion();

  useGSAP(
    () => {
      if (reduced) return;
      const el = ref.current;
      if (!el) return;
      const tl = gsap.timeline({ delay });
      const steps = 10;
      for (let i = 0; i < steps; i++) {
        tl.to(el, {
          x: gsap.utils.random(-intensity, intensity),
          y: gsap.utils.random(-intensity / 2, intensity / 2),
          rotation: gsap.utils.random(-1, 1),
          duration: duration / steps,
          ease: "none",
        });
      }
      tl.to(el, { x: 0, y: 0, rotation: 0, duration: 0.12, ease: "power2.out" });
    },
    { scope: ref, dependencies: [trigger, reduced] }
  );

  return (
    <div ref={ref} className={className}>
      {children}
    </div>
  );
}
