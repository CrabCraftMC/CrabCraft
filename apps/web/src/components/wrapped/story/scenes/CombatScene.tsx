"use client";

import { useRef, useState } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import { useHaptics } from "../hooks/useHaptics";
import DigitRoller from "../primitives/DigitRoller";
import ScreenShake from "../primitives/ScreenShake";
import Squircle from "@/components/Squircle";
import McIdTexture from "@/components/wrapped/shared/McIdTexture";
import type { WrappedData } from "@/lib/wrappedTypes";
import { getCombatJoke } from "../jokes/combatJokes";

function formatId(id: string | null | undefined) {
  if (!id) return null;
  return id.replace(/^minecraft:/, "").replace(/_/g, " ");
}

export default function CombatScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const haptics = useHaptics();
  const { mob_kills, deaths, top_mob_killed, top_death_cause } = data.stats;
  const kd = deaths > 0 ? (mob_kills / deaths).toFixed(2) : "∞";
  const [shakeKey, setShakeKey] = useState(0);

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(
          ".cb-eyebrow, .cb-kills, .cb-deaths, .cb-vs, .cb-kd, .cb-top, .cb-joke",
          { opacity: 1, x: 0, y: 0, scale: 1 }
        );
        return;
      }
      const tl = gsap.timeline();
      tl.from(".cb-eyebrow", { opacity: 0, y: 18, duration: 0.5 })
        .fromTo(
          ".cb-kills",
          { x: "-110vw", opacity: 0 },
          { x: 0, opacity: 1, duration: 0.7, ease: "power4.out" },
          "+=0.1"
        )
        .fromTo(
          ".cb-deaths",
          { x: "110vw", opacity: 0 },
          {
            x: 0,
            opacity: 1,
            duration: 0.7,
            ease: "power4.out",
            onStart: () => haptics.heavy(),
            onComplete: () => {
              setShakeKey((k) => k + 1);
              haptics.medium();
            },
          },
          "<"
        )
        .from(".cb-vs", { opacity: 0, scale: 0, rotation: -180, duration: 0.5, ease: "back.out(2)" }, "-=0.3")
        .from(".cb-kd", { opacity: 0, scale: 0.5, duration: 0.5, ease: "back.out(2)" }, "+=0.1")
        .from(".cb-top", { opacity: 0, y: 24, duration: 0.5, stagger: 0.12 }, "-=0.2")
        .from(".cb-joke", { opacity: 0, y: 14, filter: "blur(8px)", duration: 0.5 }, "-=0.1");
    },
    { scope: ref, dependencies: [reduced] }
  );

  return (
    <SceneShell id="combat" title="Combat" ref={ref}>
      <ScreenShake trigger={shakeKey} duration={0.5} intensity={10}>
        <div className="text-center">
          <p className="cb-eyebrow text-xs uppercase tracking-[0.5em] dark:text-white/60 text-stone-600">
            In combat, you
          </p>

          <div className="relative mt-10 grid grid-cols-2 items-center gap-4 sm:gap-12">
            <div className="cb-kills text-right">
              <p className="font-mc text-4xl font-bold tabular-nums dark:text-rose-200 text-rose-600 sm:text-8xl">
                <DigitRoller value={mob_kills} duration={1.4} delay={1.0} />
              </p>
              <p className="mt-2 text-xs uppercase tracking-widest dark:text-white/60 text-stone-600 sm:text-sm">
                Mob kills
              </p>
            </div>
            <div className="cb-deaths text-left">
              <p className="font-mc text-4xl font-bold tabular-nums dark:text-rose-300 text-rose-600 sm:text-8xl">
                <DigitRoller value={deaths} duration={1.4} delay={1.0} />
              </p>
              <p className="mt-2 text-xs uppercase tracking-widest dark:text-white/60 text-stone-600 sm:text-sm">
                Deaths
              </p>
            </div>
            <div
              aria-hidden
              className="cb-vs pointer-events-none absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 font-mc text-3xl font-bold dark:text-white/30 text-stone-400 sm:text-5xl"
            >
              ⚔
            </div>
          </div>

          <p className="cb-kd mt-10 text-3xl dark:text-white/80 text-stone-700 sm:text-4xl">
            K/D <span className="font-mc dark:text-orange-400 text-orange-600">{kd}</span>
          </p>

          <div className="mx-auto mt-8 grid max-w-2xl gap-3 sm:grid-cols-2">
            {top_mob_killed && (
              <Squircle cornerRadius={16} className="cb-top flex items-center gap-3 dark:bg-white/10 bg-black/10 p-3 backdrop-blur-sm">
                <McIdTexture id={top_mob_killed.id} size={36} />
                <div className="min-w-0 text-left">
                  <p className="text-[10px] uppercase tracking-widest dark:text-white/60 text-stone-600">
                    Most slain
                  </p>
                  <p className="truncate text-sm capitalize dark:text-stone-100 text-stone-800">
                    {formatId(top_mob_killed.id)}
                  </p>
                </div>
              </Squircle>
            )}
            {top_death_cause && (
              <Squircle cornerRadius={16} className="cb-top flex items-center gap-3 dark:bg-white/10 bg-black/10 p-3 backdrop-blur-sm">
                <McIdTexture id={top_death_cause.id} size={36} />
                <div className="min-w-0 text-left">
                  <p className="text-[10px] uppercase tracking-widest dark:text-white/60 text-stone-600">
                    Biggest threat
                  </p>
                  <p className="truncate text-sm capitalize dark:text-stone-100 text-stone-800">
                    {formatId(top_death_cause.id)}
                  </p>
                </div>
              </Squircle>
            )}
          </div>

          <p className="cb-joke mt-8 text-balance text-base italic dark:text-white/70 text-stone-600 sm:text-lg">
            {getCombatJoke(mob_kills, deaths)}
          </p>
        </div>
      </ScreenShake>
    </SceneShell>
  );
}
