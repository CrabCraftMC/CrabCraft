"use client";

import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import DigitRoller from "../primitives/DigitRoller";
import type { WrappedData } from "@/lib/wrappedTypes";
import { getDistanceJoke } from "../jokes/distanceJokes";

interface Mode {
  key: keyof WrappedData["stats"];
  label: string;
  texture: string;
  color: string;
}

const MODES: Mode[] = [
  { key: "walk_distance_m", label: "Walked", texture: "/minecraft/item/leather_boots.png", color: "bg-emerald-400" },
  { key: "sprint_distance_m", label: "Sprinted", texture: "/minecraft/mob_effect/speed.png", color: "bg-amber-400" },
  { key: "boat_distance_m", label: "By boat", texture: "/minecraft/item/oak_boat.png", color: "bg-orange-400" },
  { key: "elytra_distance_m", label: "Elytra", texture: "/minecraft/item/elytra.png", color: "bg-fuchsia-400" },
  { key: "horse_distance_m", label: "On horse", texture: "/minecraft/item/saddle.png", color: "bg-yellow-400" },
  { key: "swim_distance_m", label: "Swam", texture: "/minecraft/item/heart_of_the_sea.png", color: "bg-cyan-400" },
];

function planetaryComparison(km: number): string | null {
  if (km > 384400) return `That's ${(km / 384400).toFixed(1)}× to the Moon.`;
  if (km > 40075) return `That's ${(km / 40075).toFixed(1)}× around the Earth.`;
  if (km > 21196) return "That's longer than the Great Wall of China.";
  if (km > 100) return "That's a full Tour de France stage.";
  if (km > 42) return "Marathon distance, give or take.";
  return null;
}

export default function DistanceScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const km = data.stats.total_distance_m / 1000;
  const rank = data.ranks.total_distance_m;
  const comparison = planetaryComparison(km);

  const modes = MODES.map((m) => ({
    ...m,
    value: Math.max(0, data.stats[m.key] as number),
  })).filter((m) => m.value > 0);
  const maxMode = Math.max(1, ...modes.map((m) => m.value));

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(
          ".dist-eyebrow, .dist-number, .dist-unit, .dist-compare, .dist-meta, .dist-row, .dist-bar-fill, .dist-joke",
          { opacity: 1, y: 0, x: 0, scaleX: 1 }
        );
        return;
      }
      const tl = gsap.timeline();
      tl.from(".dist-eyebrow", { opacity: 0, y: 18, duration: 0.5 })
        .from(
          ".dist-number",
          { opacity: 0, scale: 0.7, duration: 0.6, ease: "back.out(2)" },
          "-=0.2"
        )
        .from(".dist-unit", { opacity: 0, x: 16, duration: 0.4 }, "-=0.2")
        .from(".dist-compare", { opacity: 0, y: 12, duration: 0.4 }, "-=0.05")
        .from(
          ".dist-row",
          { opacity: 0, x: -40, duration: 0.45, ease: "back.out(1.4)", stagger: 0.07 },
          "+=0.1"
        )
        .fromTo(
          ".dist-bar-fill",
          { scaleX: 0 },
          { scaleX: 1, duration: 0.7, ease: "back.out(1.6)", stagger: 0.07 },
          "<"
        )
        .from(".dist-meta", { opacity: 0, y: 12, duration: 0.4 }, "-=0.1")
        .from(".dist-joke", { opacity: 0, y: 14, filter: "blur(8px)", duration: 0.5 }, "-=0.1");
    },
    { scope: ref, dependencies: [reduced] }
  );

  return (
    <SceneShell id="distance" title="Distance" ref={ref}>
      <div className="text-center">
        <p className="dist-eyebrow font-mc text-xs uppercase tracking-[0.5em] text-white/60">
          You traveled
        </p>
        <p className="mt-6 font-mc text-6xl font-bold text-orange-200 sm:text-8xl lg:text-9xl">
          <span className="dist-number inline-block">
            <DigitRoller value={Math.round(km)} duration={1.5} delay={0.5} />
          </span>
          <span className="dist-unit ml-3 inline-block text-3xl text-white/70 sm:text-4xl">
            km
          </span>
        </p>
        {comparison && (
          <p className="dist-compare mt-3 text-base text-orange-300/90 sm:text-lg">
            {comparison}
          </p>
        )}

        <div className="mx-auto mt-10 max-w-xl space-y-3 text-left">
          {modes.map((m) => {
            const pct = (m.value / maxMode) * 100;
            const kmMode = (m.value / 1000).toFixed(1);
            return (
              <div key={m.key} className="dist-row flex items-center gap-3">
                <img
                  src={m.texture}
                  alt=""
                  width={20}
                  height={20}
                  className="pixelated shrink-0"
                />
                <div className="min-w-0 flex-1">
                  <div className="mb-1 flex items-baseline justify-between text-xs">
                    <span className="text-white/70">{m.label}</span>
                    <span className="tabular-nums text-white/80">{kmMode} km</span>
                  </div>
                  <div className="h-1.5 overflow-hidden rounded-full bg-white/10">
                    <div
                      className={`dist-bar-fill h-full origin-left rounded-full ${m.color}`}
                      style={{ width: `${pct}%`, transform: "scaleX(0)" }}
                    />
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        <p className="dist-meta mt-6 text-sm text-white/60">
          Ranked <span className="font-mc font-bold text-white">#{rank}</span> of {data.totalPlayers}
        </p>

        <p className="dist-joke mt-6 text-balance text-base italic text-white/70 sm:text-lg">
          {getDistanceJoke(km)}
        </p>
      </div>
    </SceneShell>
  );
}
