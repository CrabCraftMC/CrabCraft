"use client";

import dynamic from "next/dynamic";
import { useMemo, useRef } from "react";
import { useGSAP } from "@gsap/react";
import SceneShell from "./SceneShell";
import { gsap } from "@/lib/gsap";
import { useReducedMotion } from "../hooks/useReducedMotion";
import { useHaptics } from "../hooks/useHaptics";
import type { WrappedData } from "@/lib/wrappedTypes";
import { getRankingsJoke } from "../jokes/rankingsJokes";

const Fireworks = dynamic(
  () => import("@fireworks-js/react").then((m) => m.Fireworks),
  { ssr: false, loading: () => null }
);

const CATEGORIES: Array<{ key: string; label: string; icon: string }> = [
  { key: "play_time_seconds", label: "Play Time", icon: "/minecraft/item/clock_16.png" },
  { key: "total_blocks_mined", label: "Mining", icon: "/minecraft/item/diamond_pickaxe.png" },
  { key: "mob_kills", label: "Kills", icon: "/minecraft/item/diamond_sword.png" },
  { key: "total_distance_m", label: "Distance", icon: "/minecraft/item/compass_16.png" },
  { key: "total_items_crafted", label: "Crafting", icon: "/minecraft/block/crafting_table_front.png" },
  { key: "deaths", label: "Deaths", icon: "/minecraft/item/bone.png" },
];

export default function RankingsScene({ data }: { data: WrappedData }) {
  const ref = useRef<HTMLElement>(null);
  const reduced = useReducedMotion();
  const haptics = useHaptics();
  const total = data.totalPlayers;

  const ranked = useMemo(
    () =>
      CATEGORIES.map((c) => ({
        ...c,
        rank: data.ranks[c.key] ?? 0,
      })),
    [data.ranks]
  );
  const top3 = useMemo(
    () =>
      ranked.filter((r) => r.rank > 0 && r.rank <= 3).sort((a, b) => a.rank - b.rank),
    [ranked]
  );
  const others = useMemo(
    () => ranked.filter((r) => !(r.rank > 0 && r.rank <= 3)),
    [ranked]
  );
  const hasRank1 = top3.some((r) => r.rank === 1);

  useGSAP(
    () => {
      if (reduced) {
        gsap.set(".rk-eyebrow, .rk-plinth, .rk-row, .rk-joke, .rk-meta, .rk-holo-sweep", {
          opacity: 1,
          y: 0,
        });
        return;
      }
      const tl = gsap.timeline();
      tl.from(".rk-eyebrow", { opacity: 0, y: 18, duration: 0.5 })
        .from(
          ".rk-plinth",
          {
            opacity: 0,
            yPercent: 120,
            duration: 0.9,
            ease: "expo.out",
            stagger: 0.18,
            onStart: () => haptics.heavy(),
          },
          "+=0.1"
        )
        .from(
          ".rk-row",
          { opacity: 0, x: -32, duration: 0.45, ease: "back.out(1.4)", stagger: 0.08 },
          "-=0.4"
        )
        .from(".rk-meta", { opacity: 0, y: 12, duration: 0.4 }, "-=0.2")
        .from(".rk-joke", { opacity: 0, y: 14, filter: "blur(8px)", duration: 0.5 }, "-=0.1");

      if (hasRank1) {
        gsap.fromTo(
          ".rk-holo-sweep",
          { opacity: 0, x: "-100%" },
          { opacity: 1, x: "100%", duration: 1.5, ease: "power2.inOut", delay: 1.6 }
        );
      }
    },
    { scope: ref, dependencies: [reduced, hasRank1] }
  );

  const plinthHeights = [180, 140, 110];

  return (
    <SceneShell id="rankings" title="Rankings" ref={ref}>
      {top3.length > 0 && (
        <div className="pointer-events-none absolute inset-0 -z-10">
          <Fireworks
            options={{
              opacity: 0.5,
              acceleration: 1.0,
              particles: 60,
              intensity: 22,
              explosion: 5,
              hue: { min: 30, max: 70 },
              traceLength: 3,
            }}
            style={{
              top: 0,
              left: 0,
              width: "100%",
              height: "100%",
              position: "absolute",
            }}
          />
        </div>
      )}
      {hasRank1 && (
        <div
          aria-hidden
          className="rk-holo-sweep pointer-events-none absolute inset-0 -z-5"
          style={{
            background:
              "linear-gradient(110deg, transparent 0%, rgba(255,210,120,0.18) 45%, rgba(255,255,255,0.4) 50%, rgba(180,150,255,0.18) 55%, transparent 100%)",
            mixBlendMode: "screen",
          }}
        />
      )}

      <div className="text-center">
        <p className="rk-eyebrow font-mc text-xs uppercase tracking-[0.5em] text-white/60">
          Your Rankings
        </p>

        {top3.length > 0 && (
          <div className="mt-10 flex items-end justify-center gap-4 sm:gap-8">
            {top3.map((cat, i) => (
              <div key={cat.key} className="rk-plinth flex flex-col items-center">
                <img
                  src={cat.icon}
                  alt=""
                  width={36}
                  height={36}
                  className="pixelated mb-2"
                />
                <p className="text-[10px] uppercase tracking-widest text-white/60">
                  {cat.label}
                </p>
                <p
                  className={`font-mc font-bold ${
                    cat.rank === 1
                      ? "holo-text text-5xl sm:text-7xl"
                      : "text-3xl text-amber-200 sm:text-5xl"
                  }`}
                >
                  #{cat.rank}
                </p>
                <div
                  className="mt-3 w-20 rounded-t-2xl border border-white/15 bg-gradient-to-b from-amber-500/30 to-amber-900/20 backdrop-blur-sm sm:w-28"
                  style={{
                    height: plinthHeights[i] ?? 110,
                    transform: "perspective(800px) rotateX(8deg)",
                    transformOrigin: "bottom center",
                  }}
                />
              </div>
            ))}
          </div>
        )}

        {others.length > 0 && (
          <ul className="mx-auto mt-10 max-w-md space-y-2 text-left">
            {others.map((cat) => (
              <li
                key={cat.key}
                className="rk-row flex items-center gap-3 rounded-2xl border border-white/10 bg-white/5 px-4 py-3 backdrop-blur-sm"
              >
                <img
                  src={cat.icon}
                  alt=""
                  width={20}
                  height={20}
                  className="pixelated shrink-0"
                />
                <span className="flex-1 text-sm text-white/70">{cat.label}</span>
                <span className="font-mc text-base font-bold tabular-nums text-white">
                  #{cat.rank}
                </span>
              </li>
            ))}
          </ul>
        )}

        <p className="rk-meta mt-6 text-xs text-white/40">of {total} players</p>
        <p className="rk-joke mt-6 text-balance text-base italic text-white/70 sm:text-lg">
          {getRankingsJoke(top3.length, hasRank1)}
        </p>
      </div>
    </SceneShell>
  );
}
