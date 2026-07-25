"use client";

import Fireworks from "@/components/Fireworks";

export default function CompletionFireworks() {
  return (
    <Fireworks
      className="absolute -top-16 -left-8 -right-8 bottom-0 z-20 pointer-events-none"
      options={{
        rocketsPoint: { min: 30, max: 70 },
        hue: { min: 0, max: 360 },
        particles: 28,
        intensity: 10,
        explosion: 2,
        traceLength: 2,
        traceSpeed: 8,
        flickering: 30,
        opacity: 0.6,
        brightness: { min: 55, max: 85 },
        decay: { min: 0.02, max: 0.04 },
        mouse: { click: false, move: false, max: 1 },
      }}
    />
  );
}
