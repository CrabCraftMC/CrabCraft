"use client";

import dynamic from "next/dynamic";
import { useEffect, useRef, useState } from "react";
import { SLIDE_WAVE_COLORS } from "../data/sceneOrder";

const DitherBackground = dynamic(() => import("./DitherBackground"), {
  ssr: false,
  loading: () => null,
});

interface Props {
  slide: number;
  reduced?: boolean;
}

/**
 * Tints the dither shader to the current slide. Uses a per-frame lerp from
 * the previous slide's color to the next over ~1.2s so the shader uniform
 * stays a stable mutated object (the shader bakes `waveColor` directly into
 * a `THREE.Color` and watches for tuple equality changes).
 */
export default function StoryBackground({ slide, reduced = false }: Props) {
  const [waveColor, setWaveColor] = useState<[number, number, number]>(
    SLIDE_WAVE_COLORS[slide] ?? [1, 0.5, 0.3]
  );
  const fromRef = useRef<[number, number, number]>(waveColor);
  const toRef = useRef<[number, number, number]>(waveColor);
  const startRef = useRef<number>(0);
  const durationRef = useRef<number>(reduced ? 200 : 1200);

  useEffect(() => {
    const target = SLIDE_WAVE_COLORS[slide] ?? [1, 0.5, 0.3];
    fromRef.current = [...waveColor] as [number, number, number];
    toRef.current = target;
    startRef.current = performance.now();
    durationRef.current = reduced ? 200 : 1200;

    let raf = 0;
    const tick = (t: number) => {
      const elapsed = t - startRef.current;
      const p = Math.min(1, elapsed / durationRef.current);
      // ease in-out cubic
      const eased = p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
      const [r1, g1, b1] = fromRef.current;
      const [r2, g2, b2] = toRef.current;
      setWaveColor([
        r1 + (r2 - r1) * eased,
        g1 + (g2 - g1) * eased,
        b1 + (b2 - b1) * eased,
      ]);
      if (p < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slide, reduced]);

  return (
    <div aria-hidden className="absolute inset-0">
      <DitherBackground
        waveColor={waveColor}
        waveSpeed={reduced ? 0 : 0.04}
        waveFrequency={3}
        waveAmplitude={0.32}
        colorNum={5.6}
        pixelSize={2}
        disableAnimation={reduced}
        enableMouseInteraction={!reduced}
        mouseRadius={0.3}
      />
    </div>
  );
}
