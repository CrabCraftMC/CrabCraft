"use client";

import { useRef } from "react";
import { useSlideTimeline } from "../hooks/useSlideTimeline";

interface Props {
  current: number;
  total: number;
}

export default function SegmentedProgress({ current, total }: Props) {
  const rootRef = useRef<HTMLDivElement>(null);

  useSlideTimeline(
    (gsap, reduced) => {
      const fills = gsap.utils.toArray<HTMLElement>(".sp-fill");
      fills.forEach((fill, i) => {
        if (i < current) {
          gsap.set(fill, { scaleX: 1 });
        } else if (i === current) {
          if (reduced) {
            gsap.set(fill, { scaleX: 1 });
          } else {
            gsap.fromTo(
              fill,
              { scaleX: 0 },
              { scaleX: 1, duration: 0.55, ease: "power2.out" }
            );
          }
        } else {
          gsap.set(fill, { scaleX: 0 });
        }
      });
    },
    { scope: rootRef, dependencies: [current, total] }
  );

  return (
    <div
      ref={rootRef}
      className="flex flex-1 items-center gap-1.5"
      role="progressbar"
      aria-valuenow={current + 1}
      aria-valuemin={1}
      aria-valuemax={total}
      aria-label="Story progress"
    >
      {Array.from({ length: total }, (_, i) => (
        <div
          key={i}
          className="relative h-1 flex-1 overflow-hidden rounded-full bg-white/15"
        >
          <span
            className="sp-fill absolute inset-0 origin-left rounded-full bg-gradient-to-r from-orange-400 to-orange-500"
            style={{ transform: "scaleX(0)" }}
          />
        </div>
      ))}
    </div>
  );
}
