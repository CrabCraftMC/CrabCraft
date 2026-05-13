"use client";

import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import { useHaptics } from "../hooks/useHaptics";
import DigitRoller from "../primitives/DigitRoller";
import ShockwaveRing from "../primitives/ShockwaveRing";
import type { WrappedData } from "@/lib/wrappedTypes";
import { getPlaytimeJoke } from "../jokes/playtimeJokes";

export default function PlaytimeScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const haptics = useHaptics();

  const hours = Math.floor(data.stats.play_time_seconds / 3600);
  const avgHours = Math.max(1, Math.round(data.averages.avg_play_time / 3600));
  const ratio = (hours / avgHours).toFixed(1);
  const ratioBarPct = Math.min(100, (hours / avgHours) * 50);
  const rank = data.ranks.play_time_seconds;

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(".pt-eyebrow, .pt-clock, .pt-number, .pt-unit, .pt-bar, .pt-meta, .pt-joke", {
          opacity: 1,
          y: 0,
          scale: 1,
        });
        gsap.set(".pt-bar-fill", { scaleX: ratioBarPct / 100 });
        return;
      }
      const tl = gsap.timeline();
      tl.from(".pt-eyebrow", { opacity: 0, y: 18, duration: 0.5 })
        .from(
          ".pt-clock",
          {
            rotation: -180,
            scale: 0,
            opacity: 0,
            duration: 1.1,
            ease: "back.out(1.7)",
            onStart: () => haptics.light(),
          },
          "-=0.2"
        )
        .from(
          ".pt-number",
          {
            opacity: 0,
            scale: 0.6,
            duration: 0.6,
            ease: "back.out(2)",
          },
          "-=0.5"
        )
        .from(".pt-unit", { opacity: 0, x: 16, duration: 0.4 }, "-=0.2")
        .from(".pt-bar", { opacity: 0, y: 12, duration: 0.4 }, "-=0.1")
        .fromTo(
          ".pt-bar-fill",
          { scaleX: 0 },
          {
            scaleX: ratioBarPct / 100,
            duration: 0.9,
            ease: "back.out(1.6)",
            onStart: () => haptics.light(),
          },
          "<"
        )
        .from(".pt-meta", { opacity: 0, y: 12, duration: 0.4, stagger: 0.07 }, "-=0.2")
        .from(".pt-joke", { opacity: 0, y: 14, filter: "blur(8px)", duration: 0.5 }, "-=0.1");

      // Pair the two shockwave rings (delays 1.2 / 1.35 in the JSX) with thumps.
      gsap.delayedCall(1.2, () => haptics.medium());
      gsap.delayedCall(1.35, () => haptics.light());
    },
    { scope: ref, dependencies: [reduced, ratioBarPct] }
  );

  return (
    <SceneShell id="playtime" title="Play Time" ref={ref}>
      <div className="text-center">
        <p className="pt-eyebrow text-xs uppercase tracking-[0.5em] dark:text-white/60 text-stone-600">
          You spent
        </p>

        <div className="relative mt-6 inline-block">
          <div
            aria-hidden
            className="pt-clock pointer-events-none absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 opacity-15"
          >
            <img
              src="/minecraft/item/clock_16.png"
              alt=""
              width={140}
              height={140}
              className="pixelated"
            />
          </div>
          <ShockwaveRing delay={1.2} size={120} maxScale={3.2} color="rgba(251, 191, 36, 0.6)" />
          <ShockwaveRing delay={1.35} size={120} maxScale={3.6} color="rgba(251, 146, 60, 0.5)" />
          <p className="pt-number relative z-10 font-mc text-6xl font-bold dark:text-amber-200 text-amber-700 sm:text-8xl lg:text-9xl">
            <DigitRoller value={hours} duration={1.5} delay={0.6} />
            <span className="pt-unit ml-3 inline-block text-3xl font-bold dark:text-white/70 text-stone-600 sm:text-4xl">
              hours
            </span>
          </p>
        </div>

        <div className="pt-bar mx-auto mt-10 max-w-md">
          <div className="relative h-3 overflow-hidden rounded-full dark:bg-white/10 bg-black/10">
            <div
              className="pt-bar-fill absolute inset-y-0 left-0 origin-left rounded-full bg-gradient-to-r from-amber-300 to-orange-400"
              style={{ width: "100%", transform: "scaleX(0)" }}
            />
            <div
              aria-hidden
              className="absolute inset-y-0 left-1/2 w-px dark:bg-white/40 bg-stone-700/40"
              title="Server average"
            />
          </div>
          <p className="pt-meta mt-2 text-xs dark:text-white/60 text-stone-600">
            <span className="font-bold dark:text-stone-100 text-stone-800">{ratio}×</span> the server average
          </p>
        </div>

        <p className="pt-meta mt-6 text-sm dark:text-white/60 text-stone-600">
          Ranked <span className="font-mc font-bold dark:text-stone-100 text-stone-800">#{rank}</span> of {data.totalPlayers}
        </p>

        <p className="pt-joke mt-8 text-balance text-base italic dark:text-white/70 text-stone-600 sm:text-lg">
          {getPlaytimeJoke(hours)}
        </p>
      </div>
    </SceneShell>
  );
}
