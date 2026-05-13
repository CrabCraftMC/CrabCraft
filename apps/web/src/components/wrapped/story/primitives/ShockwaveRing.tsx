"use client";

import { useGSAP } from "@gsap/react";
import { useRef } from "react";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";

interface Props {
  delay?: number;
  color?: string;
  size?: number;
  maxScale?: number;
  duration?: number;
  thickness?: number;
}

/**
 * Single expanding ring pulse. Position absolute, centered on parent.
 */
export default function ShockwaveRing({
  delay = 0,
  color = "rgba(255, 255, 255, 0.85)",
  size = 80,
  maxScale = 3,
  duration = 1.2,
  thickness = 3,
}: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const reduced = useReducedMotion();

  useGSAP(
    () => {
      if (reduced) return;
      gsap.fromTo(
        ref.current,
        { scale: 0.4, opacity: 0.9 },
        {
          scale: maxScale,
          opacity: 0,
          duration,
          delay,
          ease: "power3.out",
        }
      );
    },
    { scope: ref, dependencies: [delay, maxScale, duration] }
  );

  return (
    <span
      ref={ref}
      aria-hidden
      className="pointer-events-none absolute left-1/2 top-1/2 block rounded-full"
      style={{
        width: size,
        height: size,
        marginLeft: -size / 2,
        marginTop: -size / 2,
        border: `${thickness}px solid ${color}`,
        opacity: 0,
      }}
    />
  );
}
